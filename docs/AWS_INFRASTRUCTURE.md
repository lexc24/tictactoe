# AWS Infrastructure Guide

## Overview

This application uses a **hybrid architecture** combining traditional server infrastructure (EC2 + ALB) for the JPro application with serverless components (Lambda + API Gateway + DynamoDB) for the multiplayer queue system.

The infrastructure is provisioned via **Terraform modules** (VPC, security groups, EC2/ALB) and **CloudFormation** (DynamoDB).

## Infrastructure Diagram

```
                                    INTERNET
                                       │
                                       │ HTTPS
                                       ▼
                        ┌──────────────────────────────┐
                        │   Route 53 Hosted Zone       │
                        │   tttlexc24.it.com           │
                        │                              │
                        │   A Record:                  │
                        │   api.tttlexc24.it.com → ALB │
                        └──────────────┬───────────────┘
                                       │
                                       ▼
    ┌────────────────────────────────────────────────────────────┐
    │                      AWS REGION (us-east-1)                │
    │                                                             │
    │  VPC (10.0.0.0/16)                                         │
    │  ┌──────────────────────────────────────────────────────┐ │
    │  │                                                       │ │
    │  │  PUBLIC SUBNETS (Internet Gateway Attached)          │ │
    │  │  ┌────────────────────┐  ┌────────────────────┐     │ │
    │  │  │ us-east-1a         │  │ us-east-1b         │     │ │
    │  │  │ 10.0.1.0/24        │  │ 10.0.2.0/24        │     │ │
    │  │  │                    │  │                    │     │ │
    │  │  │  ┌──────────────┐  │  │  ┌──────────────┐  │     │ │
    │  │  │  │ ALB (Public) │◄─┼──┼──┤ ALB (Public) │  │     │ │
    │  │  │  └──────┬───────┘  │  │  └──────────────┘  │     │ │
    │  │  │         │           │  │                    │     │ │
    │  │  │         │ NAT GW    │  │     NAT GW         │     │ │
    │  │  │         │(Optional) │  │    (Optional)      │     │ │
    │  │  └─────────┼───────────┘  └────────────────────┘     │ │
    │  │            │                                          │ │
    │  │            │ HTTP (Port 80)                           │ │
    │  │            ▼                                          │ │
    │  │  PRIVATE SUBNETS (No Direct Internet Access)         │ │
    │  │  ┌────────────────────┐  ┌────────────────────┐     │ │
    │  │  │ us-east-1a         │  │ us-east-1b         │     │ │
    │  │  │ 10.0.101.0/24      │  │ 10.0.102.0/24      │     │ │
    │  │  │                    │  │                    │     │ │
    │  │  │  ┌──────────────┐  │  │                    │     │ │
    │  │  │  │ EC2 Instance │  │  │                    │     │ │
    │  │  │  │ JPro Server  │  │  │                    │     │ │
    │  │  │  │ + Nginx      │  │  │                    │     │ │
    │  │  │  └──────┬───────┘  │  │                    │     │ │
    │  │  │         │           │  │                    │     │ │
    │  │  │         ▼           │  │                    │     │ │
    │  │  │  VPC S3 Endpoint   │  │                    │     │ │
    │  │  │  (Download JAR)    │  │                    │     │ │
    │  │  └────────────────────┘  └────────────────────┘     │ │
    │  │                                                       │ │
    │  └───────────────────────────────────────────────────────┘ │
    │                                                             │
    │  REGIONAL SERVICES (Outside VPC)                           │
    │  ┌─────────────────────┐  ┌──────────────────────┐        │
    │  │ API Gateway         │  │ DynamoDB             │        │
    │  │ WebSocket API       │  │ TicTacToeUsers       │        │
    │  │ wss://xxx.execute.. │  │ + Streams            │        │
    │  └──────┬──────────────┘  └──────┬───────────────┘        │
    │         │                         │                        │
    │         ▼                         ▼                        │
    │  ┌────────────────────────────────────────┐               │
    │  │ Lambda Functions (7)                   │               │
    │  │ - connection, disconnect, joinQueue    │               │
    │  │ - gameOver, updateDB, sendInfo         │               │
    │  │ - streamDB (DynamoDB Stream trigger)   │               │
    │  └────────────────────────────────────────┘               │
    └─────────────────────────────────────────────────────────────┘
```

## Terraform Module Breakdown

### Module 1: VPC Foundation (`aws/vpc/mod1/vpct.tf`)

**Purpose:** Establishes the network foundation for the entire application.

#### Resources Created

**1. VPC (`aws_vpc.vpc`)**
```hcl
# aws/vpc/mod1/vpct.tf:7-12
resource "aws_vpc" "vpc" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
}
```
- **CIDR Block:** `10.0.0.0/16` (65,536 IP addresses)
- **DNS Support:** Required for EC2 instances to resolve public DNS names
- **DNS Hostnames:** EC2 instances get public DNS names (e.g., `ec2-XX-XX-XX-XX.compute-1.amazonaws.com`)
- **Why This Range:** Standard private network range, large enough for scaling

**2. Public Subnets (2x Availability Zones)**
```hcl
# aws/vpc/mod1/vpct.tf:14-28
resource "aws_subnet" "public_subnet_a" {
  vpc_id                  = aws_vpc.vpc.id
  cidr_block              = var.public_subnet_a_cidr  # 10.0.1.0/24
  availability_zone       = "${var.region}a"
  map_public_ip_on_launch = true
}
```
- **Subnet A:** `10.0.1.0/24` in `us-east-1a` (254 hosts)
- **Subnet B:** `10.0.2.0/24` in `us-east-1b` (254 hosts)
- **Auto-Assign Public IP:** Instances launched here get public IPs automatically
- **Why Two AZs:** High availability for ALB (requires >= 2 AZs)

**3. Private Subnets (2x Availability Zones)**
```hcl
# aws/vpc/mod1/vpct.tf:30-44
resource "aws_subnet" "private_subnet_a" {
  vpc_id                  = aws_vpc.vpc.id
  cidr_block              = var.private_subnet_a_cidr  # 10.0.101.0/24
  availability_zone       = "${var.region}a"
  map_public_ip_on_launch = false
}
```
- **Subnet A:** `10.0.101.0/24` in `us-east-1a`
- **Subnet B:** `10.0.102.0/24` in `us-east-1b`
- **No Public IP:** Security best practice (EC2 not directly accessible from internet)
- **Access Pattern:** Internet → ALB (public) → EC2 (private)

**4. Internet Gateway**
```hcl
# aws/vpc/mod1/vpct.tf:46-49
resource "aws_internet_gateway" "igw" {
  vpc_id = aws_vpc.vpc.id
}
```
- **Purpose:** Allows VPC resources to communicate with internet
- **Attached To:** Public subnets via route tables
- **Required For:** ALB to receive HTTPS traffic, NAT Gateway for outbound traffic

**5. NAT Gateways (Optional)**
```hcl
# aws/vpc/mod1/vpct.tf:51-64
resource "aws_nat_gateway" "nat_a" {
  count         = var.enable_nat_gateways ? 1 : 0
  allocation_id = aws_eip.nat_a[0].id
  subnet_id     = aws_subnet.public_subnet_a.id
}
```
- **Default:** Disabled (`enable_nat_gateways = false`)
- **When Enabled:** Allows private subnet resources to access internet
- **Cost:** ~$32/month per NAT Gateway + data transfer fees
- **Use Case:** EC2 instance needs to download software updates, pull Docker images, etc.
- **Alternative:** VPC endpoints for AWS services (cheaper)

**6. Route Tables**
```hcl
# Public Route Table (aws/vpc/mod1/vpct.tf:81-95)
resource "aws_route" "public_route" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.igw.id
}
```
- **Public Route:** `0.0.0.0/0` → Internet Gateway (all outbound traffic goes to internet)
- **Private Route:** `0.0.0.0/0` → NAT Gateway (if enabled) or no default route
- **Subnet Associations:** Links route tables to specific subnets

**7. VPC Flow Logs**
```hcl
# aws/vpc/mod1/vpct.tf:123-139
resource "aws_flow_log" "vpc_flow_log" {
  iam_role_arn    = aws_iam_role.vpc_flow_log_role.arn
  log_destination = aws_cloudwatch_log_group.vpc_flow_log_group.arn
  traffic_type    = "ALL"
  vpc_id          = aws_vpc.vpc.id
}
```
- **Traffic Types:** ALL (accepted + rejected)
- **Destination:** CloudWatch Logs
- **Purpose:** Network troubleshooting, security analysis, compliance
- **What It Captures:** Source/dest IPs, ports, protocol, bytes, packets, action (ACCEPT/REJECT)

#### Key Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `region` | `us-east-1` | AWS region for all resources |
| `vpc_cidr` | `10.0.0.0/16` | VPC IP range |
| `public_subnet_a_cidr` | `10.0.1.0/24` | Public subnet AZ-a |
| `public_subnet_b_cidr` | `10.0.2.0/24` | Public subnet AZ-b |
| `private_subnet_a_cidr` | `10.0.101.0/24` | Private subnet AZ-a |
| `private_subnet_b_cidr` | `10.0.102.0/24` | Private subnet AZ-b |
| `enable_nat_gateways` | `false` | Enable NAT for private subnets |

---

### Module 2: Security Groups (`aws/vpc/mod2/sgt.tf`)

**Purpose:** Define firewall rules controlling network traffic to/from EC2 and ALB.

#### ALB Security Group

```hcl
# aws/vpc/mod2/sgt.tf:7-42
resource "aws_security_group" "alb_sg" {
  name        = "alb-sg"
  description = "Security group for Application Load Balancer"
  vpc_id      = var.vpc_id

  ingress {
    description = "Allow HTTPS from anywhere"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "Allow HTTP from anywhere"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
```

**Inbound Rules:**
- **Port 443 (HTTPS):** Accept from `0.0.0.0/0` (entire internet)
- **Port 80 (HTTP):** Accept from `0.0.0.0/0` (redirects to HTTPS)

**Outbound Rules:**
- **All Traffic:** Allows ALB to forward to EC2 instances

**Why Allow HTTP?**
- ALB listener automatically redirects HTTP → HTTPS
- Ensures users typing `http://api.tttlexc24.it.com` get redirected to HTTPS

#### JPro/EC2 Security Group

```hcl
# aws/vpc/mod2/sgt.tf:44-79
resource "aws_security_group" "jpro_sg" {
  name        = "jpro-sg"
  description = "Security group for JPro/EC2 instance"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Allow HTTP from ALB"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [aws_security_group.alb_sg.id]
  }

  ingress {
    description = "Allow SSH from allowed CIDR"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.allowed_ssh_cidr]
  }

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
```

**Inbound Rules:**
- **Port 80:** Only from ALB security group (not entire internet)
- **Port 22 (SSH):** From `allowed_ssh_cidr` (default: `0.0.0.0/0`, should be restricted)

**Outbound Rules:**
- **All Traffic:** EC2 can make outbound requests (software updates, S3 downloads)

**Why Port 80 Instead of 8080?**
- Nginx reverse proxy runs on port 80
- JPro application runs on port 8080 (localhost only)
- ALB forwards to port 80 → Nginx proxies to localhost:8080

**Security Best Practice Violation:**
- SSH from `0.0.0.0/0` allows brute-force attempts
- **Should Be:** Your office/home IP (e.g., `203.0.113.0/24`)
- **Production Fix:** Use AWS Systems Manager Session Manager (no SSH port needed)

#### Key Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `vpc_id` | (from mod1) | VPC to attach security groups |
| `allowed_ssh_cidr` | `0.0.0.0/0` | CIDR block for SSH access |

---

### Module 3: Compute & Load Balancing (`aws/vpc/mod3/ec2.tf`)

**Purpose:** Provision EC2 instance, ALB, HTTPS certificates, and DNS records.

#### EC2 Instance

```hcl
# aws/vpc/mod3/ec2.tf:19-32
resource "aws_instance" "jpro_instance" {
  ami                    = var.ami_id
  instance_type          = var.instance_type
  subnet_id              = var.private_subnets[0]
  vpc_security_group_ids = [var.jpro_security_group_id]
  iam_instance_profile   = aws_iam_instance_profile.ec2_instance_profile.name
  key_name               = var.key_name
  user_data              = file("${path.module}/user_data.sh")

  tags = {
    Name = "jpro-instance"
  }
}
```

**Configuration:**
- **AMI:** Amazon Linux 2 (configurable via `ami_id` variable)
- **Instance Type:** `t2.micro` or `t2.small` (configurable)
- **Placement:** Private subnet (no public IP)
- **IAM Role:** Attached via instance profile (see below)
- **User Data:** Bootstrap script runs on first boot

**Why Private Subnet?**
- **Security:** EC2 not directly accessible from internet
- **Access Pattern:** All HTTP/HTTPS traffic flows through ALB
- **Trade-off:** Requires bastion host or VPC endpoint for management

#### IAM Role for EC2

```hcl
# aws/vpc/mod3/ec2.tf:1-17
resource "aws_iam_role" "ec2_role" {
  name = "ec2-jpro-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "s3_read_only" {
  role       = aws_iam_role.ec2_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess"
}
```

**Permissions:**
- **S3 Read-Only:** Allows EC2 to download JAR file from `s3://tttbucket-lexc24/TTTG.jar`
- **Why Not Full Access:** Principle of least privilege
- **Alternative:** Could use S3 VPC endpoint to avoid internet routing

#### User Data Script (`aws/vpc/mod3/user_data.sh`)

```bash
# aws/vpc/mod3/user_data.sh:1-50 (excerpts)
#!/bin/bash

# Install Java 17
sudo yum install -y java-17-amazon-corretto-headless

# Install Nginx
sudo amazon-linux-extras install -y nginx1

# Download JAR from S3
aws s3 cp s3://tttbucket-lexc24/TTTG.jar /home/ec2-user/TTTG.jar

# Create systemd service for JPro
sudo bash -c 'cat > /etc/systemd/system/jpro.service <<EOF
[Service]
ExecStart=/usr/bin/java -jar /home/ec2-user/TTTG.jar
Restart=always
User=ec2-user
Environment=JPRO_PORT=8080
EOF'

# Configure Nginx reverse proxy
sudo bash -c 'cat > /etc/nginx/conf.d/jpro.conf <<EOF
server {
    listen 80;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host \$host;
        add_header Access-Control-Allow-Origin "https://tttlexc24.it.com";
    }
}
EOF'

# Start services
sudo systemctl enable jpro nginx
sudo systemctl start jpro nginx
```

**What It Does:**
1. **Install Dependencies:** Java 17, Nginx, Git, AWS CLI
2. **Download Application:** Pulls `TTTG.jar` from S3
3. **Create JPro Service:** Runs JAR on port 8080, auto-restart on failure
4. **Configure Nginx:** Reverse proxy port 80 → localhost:8080
5. **CORS Headers:** Allows requests from `tttlexc24.it.com`
6. **Start Services:** Enable auto-start on boot

**Why Nginx?**
- **HTTPS Termination Handled by ALB:** Nginx only handles HTTP
- **Reverse Proxy Pattern:** Separates web server from application server
- **Future Extensibility:** Could add rate limiting, caching, custom headers

#### Application Load Balancer

```hcl
# aws/vpc/mod3/ec2.tf:34-59
resource "aws_lb" "alb" {
  name               = "ttt-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.alb_security_group_id]
  subnets            = var.public_subnets

  enable_deletion_protection = false
}

resource "aws_lb_target_group" "jpro_tg" {
  name     = "jpro-target-group"
  port     = 80
  protocol = "HTTP"
  vpc_id   = var.vpc_id

  health_check {
    path                = "/"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 2
  }

  stickiness {
    type            = "lb_cookie"
    cookie_duration = 86400
    enabled         = true
  }
}
```

**ALB Configuration:**
- **Type:** Application Load Balancer (Layer 7, HTTP/HTTPS)
- **Subnets:** Both public subnets (AZ-a and AZ-b) for high availability
- **Internal:** `false` (internet-facing)

**Target Group:**
- **Port:** 80 (Nginx)
- **Health Check:** GET request to `/` every 30 seconds
  - **Healthy:** 2 consecutive 200 OK responses
  - **Unhealthy:** 2 consecutive failures
- **Stickiness:** Enabled for 24 hours (86400 seconds)

**Why Sticky Sessions?**
- **JPro Requirement:** Each user needs consistent connection to same EC2 instance
- **WebSocket Support:** Ensures WebSocket connections don't break on request redistribution
- **Single Instance:** Not strictly needed with 1 instance, but prepares for Auto Scaling

#### HTTPS Listener & Redirect

```hcl
# aws/vpc/mod3/ec2.tf:68-107
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.alb.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-2016-08"
  certificate_arn   = aws_acm_certificate.cert.arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.jpro_tg.arn
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.alb.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}
```

**HTTPS Listener (Port 443):**
- **SSL Policy:** `ELBSecurityPolicy-2016-08` (supports TLS 1.2+, modern ciphers)
- **Certificate:** ACM-managed certificate for `api.tttlexc24.it.com`
- **Action:** Forward decrypted traffic to target group

**HTTP Listener (Port 80):**
- **Action:** 301 permanent redirect to HTTPS
- **Why 301 Not 302:** Browsers cache permanent redirects (performance)

#### Route 53 DNS

```hcl
# aws/vpc/mod3/ec2.tf:109-125
resource "aws_route53_zone" "main" {
  name = "tttlexc24.it.com"
}

resource "aws_route53_record" "api" {
  zone_id = aws_route53_zone.main.zone_id
  name    = "api.tttlexc24.it.com"
  type    = "A"

  alias {
    name                   = aws_lb.alb.dns_name
    zone_id                = aws_lb.alb.zone_id
    evaluate_target_health = true
  }
}
```

**Hosted Zone:** `tttlexc24.it.com`
**A Record:** `api.tttlexc24.it.com` → ALB DNS name (alias record)

**Why Alias Record?**
- **No IP Address:** ALB IPs can change, alias automatically updates
- **No Charge:** Alias queries are free (CNAME queries cost $0.40 per million)
- **Health Check:** `evaluate_target_health` routes traffic only if ALB healthy

#### ACM Certificate

```hcl
# aws/vpc/mod3/ec2.tf:127-149
resource "aws_acm_certificate" "cert" {
  domain_name       = "api.tttlexc24.it.com"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.cert.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  zone_id = aws_route53_zone.main.zone_id
  name    = each.value.name
  type    = each.value.type
  records = [each.value.record]
  ttl     = 60
}
```

**Validation:** DNS (requires adding CNAME record to Route 53)
**Renewal:** ACM auto-renews before expiration
**Cost:** Free for use with AWS services

**Why DNS Validation (Not Email)?**
- **Automation:** Terraform creates validation records automatically
- **No Manual Steps:** Email validation requires clicking link
- **Renewal:** ACM auto-validates before expiration

#### VPC S3 Endpoint

```hcl
# aws/vpc/mod3/ec2.tf:151-165
resource "aws_vpc_endpoint" "s3" {
  vpc_id       = var.vpc_id
  service_name = "com.amazonaws.${var.region}.s3"

  route_table_ids = var.private_route_table_ids
}
```

**Purpose:** Allows private subnet EC2 to access S3 without internet gateway
**Type:** Gateway endpoint (no hourly charge)
**Route:** Automatically adds route to S3 service in private route tables

**Why Not NAT Gateway?**
- **Cost:** VPC endpoints are free, NAT Gateway costs ~$32/month
- **Performance:** Direct connection to S3 (lower latency)
- **Security:** Traffic never leaves AWS network

---

### CloudFormation: DynamoDB Table (`aws/template.yaml`)

**Purpose:** Define DynamoDB table for game queue state with Streams.

#### Table Configuration

```yaml
# aws/template.yaml:5-46
TicTacToeUsersTable:
  Type: AWS::DynamoDB::Table
  Properties:
    TableName: TicTacToeUsers
    BillingMode: PAY_PER_REQUEST
    AttributeDefinitions:
      - AttributeName: connectionId
        AttributeType: S
      - AttributeName: status
        AttributeType: S
      - AttributeName: joinedAt
        AttributeType: S
    KeySchema:
      - AttributeName: connectionId
        KeyType: HASH
    GlobalSecondaryIndexes:
      - IndexName: statusIndex
        KeySchema:
          - AttributeName: status
            KeyType: HASH
          - AttributeName: joinedAt
            KeyType: RANGE
        Projection:
          ProjectionType: ALL
    StreamSpecification:
      StreamViewType: NEW_AND_OLD_IMAGES
    SSESpecification:
      SSEEnabled: true
```

**Key Design Decisions:**

**1. Billing Mode: PAY_PER_REQUEST**
- **Why Not Provisioned:** Unpredictable traffic (players join/leave randomly)
- **Cost:** $1.25 per million write requests, $0.25 per million read requests
- **Break-Even:** If < 1000 writes/sec sustained, on-demand is cheaper

**2. Primary Key: `connectionId` (String)**
- **Uniqueness:** API Gateway assigns unique ID per WebSocket connection
- **Query Pattern:** `GetItem` by connectionId when user disconnects or updates username

**3. Global Secondary Index: `statusIndex`**
```yaml
Partition Key: status ('active' or 'inactive')
Sort Key: joinedAt (ISO 8601 timestamp)
```
- **Query Pattern:** "Get all inactive users sorted by join time" (`disconnect.py:56`, `gameOver.py:45`)
- **Why GSI:** Can't query by non-key attribute in base table
- **Projection:** ALL (includes all attributes, no need for second query)

**4. DynamoDB Streams**
```yaml
StreamViewType: NEW_AND_OLD_IMAGES
```
- **Triggers:** `streamDB.py` Lambda on INSERT/MODIFY/REMOVE
- **View Type:** Includes both old and new item state (useful for debugging)
- **Retention:** 24 hours (Lambda has 24 hours to process before data loss)

**5. Server-Side Encryption**
- **Default:** AWS-managed keys (no extra cost)
- **Compliance:** Encrypts data at rest
- **Performance:** No impact (transparent encryption)

#### Table Attributes (Runtime)

| Attribute | Type | Purpose | Example |
|-----------|------|---------|---------|
| `connectionId` | String | Primary key, WebSocket connection ID | `abc123xyz` |
| `sessionId` | String | UUID for additional tracking | `550e8400-e29b-41d4-a716-446655440000` |
| `username` | String | Player's display name | `Player1` |
| `status` | String | Active (playing) or inactive (waiting) | `active` |
| `marker` | String | X, O, or null | `X` |
| `joinedAt` | String | ISO timestamp for queue ordering | `2025-01-15T10:30:00Z` |

---

## AWS Services NOT in VPC

### API Gateway WebSocket API

**Endpoint:** `wss://wqritmruc9.execute-api.us-east-1.amazonaws.com/production`

**Routes:**
```
$connect        → connection.py
$disconnect     → disconnect.py
joinQueue       → joinQueue.py
gameOVER        → gameOver.py
updateDB        → updateDB.py
sendInfo        → sendInfo.py
```

**Why WebSocket (Not HTTP REST)?**
- **Persistent Connections:** Clients stay connected for real-time updates
- **Server Push:** Backend can send queue updates without client polling
- **Efficiency:** Single connection vs. repeated HTTP requests
- See `docs/adr/001-websocket-vs-rest-api.md`

**Connection Lifecycle:**
1. Client connects → `$connect` route
2. Client sends messages → Custom routes (e.g., `updateDB`)
3. Client disconnects (or timeout) → `$disconnect` route

### Lambda Functions

**Execution Role Permissions:**
- `dynamodb:PutItem`, `Query`, `UpdateItem`, `DeleteItem`, `Scan`
- `execute-api:ManageConnections` (for WebSocket `POST /@connections/{connectionId}`)
- CloudWatch Logs write permissions

**Concurrency:**
- **Reserved Concurrency:** None (uses account default)
- **Scaling:** Auto-scales to match incoming WebSocket connections
- **Cold Starts:** ~1-2 seconds for Python Lambdas

**Missing Component:**
- `utility.py` layer with shared code (DynamoDB table reference, send_message function)

---

## Communication Patterns

### Pattern 1: Client → Lambda → DynamoDB

```
Browser (app.js)
  │ WebSocket message: updateDB
  ▼
API Gateway
  │ Routes to Lambda
  ▼
updateDB.py
  │ DynamoDB UpdateItem
  ▼
TicTacToeUsers Table
```

**Example:** User updates username
1. `app.js:60` sends `{"action": "updateDB", "username": "Player1"}`
2. API Gateway invokes `updateDB.py`
3. Lambda updates DynamoDB item
4. DynamoDB Stream triggers `streamDB.py` (Pattern 3)

### Pattern 2: Browser → ALB → EC2 (JPro)

```
Browser (index.html)
  │ HTTPS: GET https://api.tttlexc24.it.com/app/TTT
  ▼
Route 53
  │ Resolves to ALB
  ▼
Application Load Balancer
  │ SSL termination, forward to target group
  ▼
EC2 Instance (Private Subnet)
  │ Nginx reverse proxy
  ▼
JPro Application (Port 8080)
  │ Renders JavaFX as HTML5/WebSocket
  ▼
Browser displays game UI
```

**Example:** Loading game board
1. User navigates to `https://api.tttlexc24.it.com`
2. ALB terminates HTTPS, forwards HTTP to EC2 port 80
3. Nginx proxies to JPro on localhost:8080
4. JPro serves JavaFX application

### Pattern 3: DynamoDB Streams → Lambda → WebSocket Broadcast

```
DynamoDB TicTacToeUsers Table
  │ INSERT/MODIFY/REMOVE event
  ▼
DynamoDB Streams
  │ Triggers Lambda (event source mapping)
  ▼
streamDB.py
  │ Scans table, sorts by joinedAt
  ▼
API Gateway ManageConnections API
  │ POST /@connections/{connectionId}
  ▼
All Connected WebSocket Clients
```

**Example:** Player disconnects
1. `disconnect.py` deletes DynamoDB item
2. DynamoDB Stream emits REMOVE event
3. `streamDB.py:20` processes event
4. Lambda scans table, gets current queue state
5. Sends `queueUpdate` to all connectionIds via API Gateway
6. All browsers update UI via `app.js:updateQueueUI()`

---

## Cost Breakdown (Estimated Monthly)

| Service | Configuration | Estimated Cost |
|---------|---------------|----------------|
| **EC2** | t2.micro (750 hrs free tier) | $0 - $8.50 |
| **ALB** | 730 hours + 1 LCU | $16.20 + $5.84 = $22.04 |
| **Route 53** | 1 hosted zone + queries | $0.50 + minimal |
| **DynamoDB** | On-demand (low traffic) | < $1 |
| **Lambda** | 1M requests/month | Free tier |
| **API Gateway** | WebSocket (1M messages) | $1.00 |
| **VPC** | No NAT Gateway | $0 |
| **ACM** | 1 certificate | $0 |
| **CloudWatch Logs** | < 5 GB | < $2.50 |
| **Data Transfer** | Out to internet | ~$1-5 |
| **Total** | | **~$30-35/month** |

**Most Expensive:** ALB (~$22/month)
**Alternative:** Use CloudFront + Lambda@Edge (might reduce ALB cost)

**If Enabling NAT Gateway:**
- Add $32.40/month per AZ ($64.80 for 2 AZs)
- Total would jump to ~$95-100/month

---

## Security Best Practices

### Implemented
- ✅ Private subnets for application servers
- ✅ Security groups with least privilege
- ✅ HTTPS everywhere (ALB termination)
- ✅ DynamoDB encryption at rest
- ✅ VPC Flow Logs for monitoring
- ✅ IAM roles with minimal permissions

### Missing/Weak
- ⚠️ SSH from `0.0.0.0/0` (should be restricted)
- ⚠️ No Web Application Firewall (WAF) on ALB
- ⚠️ No DDoS protection (should use AWS Shield)
- ⚠️ Hardcoded endpoints in Lambda/JS (should use environment variables)
- ⚠️ No Lambda function timeout/rate limiting
- ⚠️ No CloudTrail for API auditing

---

## High Availability & Disaster Recovery

### Current State
- **ALB:** Multi-AZ (us-east-1a, us-east-1b)
- **EC2:** Single instance in us-east-1a (single point of failure)
- **DynamoDB:** Multi-AZ by default (auto-replication)
- **Lambda:** Multi-AZ by default

### Failure Scenarios

| Failure | Impact | Mitigation |
|---------|--------|------------|
| **EC2 instance crash** | Game UI down, queue system works | Auto Scaling Group + multiple instances |
| **AZ-a outage** | EC2 down, ALB routes to AZ-b (no instances) | Launch instances in multiple AZs |
| **Region outage** | Complete service down | Multi-region deployment (Route 53 failover) |
| **DynamoDB throttling** | Queue updates delayed/fail | Use provisioned capacity with auto-scaling |
| **Lambda errors** | Specific features break | Enable DLQ, CloudWatch alarms, retry logic |

### Production Recommendations
1. **Auto Scaling Group:** 2-3 EC2 instances across AZs
2. **RDS/DynamoDB Backups:** Enable point-in-time recovery
3. **CloudWatch Alarms:** Monitor ALB 5xx errors, Lambda failures
4. **Multi-Region:** Active-passive failover for critical uptime

---

## Deployment Workflow

### Infrastructure Provisioning

```bash
# Module 1: VPC
cd aws/vpc/mod1
terraform init
terraform apply -var="enable_nat_gateways=false"

# Module 2: Security Groups
cd ../mod2
terraform init
terraform apply -var="vpc_id=<vpc-id-from-mod1>"

# Module 3: EC2 + ALB
cd ../mod3
terraform init
terraform apply \
  -var="vpc_id=<vpc-id>" \
  -var="public_subnets=[<subnet-ids>]" \
  -var="private_subnets=[<subnet-ids>]"

# DynamoDB
aws cloudformation deploy \
  --template-file aws/template.yaml \
  --stack-name tictactoe-dynamodb
```

### Application Deployment

1. **Build JAR:** Compile JavaFX + JPro application
2. **Upload to S3:** `aws s3 cp TTTG.jar s3://tttbucket-lexc24/`
3. **Launch EC2:** Terraform provisions instance, user data downloads JAR
4. **Deploy Lambdas:** Package functions + `utility.py` layer, upload to Lambda
5. **Configure API Gateway:** Create WebSocket API, attach Lambda integrations
6. **Update DNS:** Point `api.tttlexc24.it.com` to ALB (Terraform does this)

---

## Related Documentation

- **Lambda Function Details:** `LAMBDA_FUNCTIONS.md`
- **WebSocket vs REST Decision:** `adr/001-websocket-vs-rest-api.md`
- **DynamoDB Streams Pattern:** `adr/002-dynamodb-streams-for-realtime.md`
- **Terraform Module Strategy:** `adr/004-terraform-infrastructure-choices.md`
