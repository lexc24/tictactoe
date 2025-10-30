# ADR-004: Terraform Module Organization and Infrastructure Choices

## Status
Accepted

## Context

The application infrastructure requires:
- **Networking:** VPC, subnets, internet gateway, route tables, NAT gateways
- **Security:** Security groups for ALB and EC2 instances
- **Compute:** EC2 instance for JPro application server
- **Load Balancing:** Application Load Balancer with HTTPS termination
- **DNS:** Route 53 hosted zone and records
- **Certificates:** ACM SSL/TLS certificate for HTTPS
- **Storage:** DynamoDB table for user state

We evaluated several Infrastructure as Code (IaC) approaches:
1. **Terraform with modular structure** (3 separate modules)
2. **Single monolithic Terraform file**
3. **CloudFormation for all resources**
4. **AWS CDK (TypeScript/Python)**
5. **Pulumi (TypeScript/Python/Go)**

## Decision

**Use Terraform with 3-module organization + CloudFormation for DynamoDB:**

**Structure:**
```
aws/
├── vpc/
│   ├── mod1/vpct.tf          # VPC, subnets, NAT, routing
│   ├── mod2/sgt.tf           # Security groups
│   └── mod3/ec2.tf           # EC2, ALB, Route 53, ACM
└── template.yaml             # CloudFormation for DynamoDB
```

**Deployment Order:**
1. `terraform apply` in `mod1/` (VPC infrastructure)
2. `terraform apply` in `mod2/` (security groups, depends on VPC ID)
3. `terraform apply` in `mod3/` (EC2/ALB, depends on VPC ID + SG IDs)
4. `aws cloudformation deploy` (DynamoDB table)

## Why This Approach

### Separate Modules (Not Single File)

**Current Structure (3 Modules):**
```
mod1/vpct.tf:  150 lines (VPC, subnets, NAT, IGW, routes)
mod2/sgt.tf:    80 lines (ALB SG, EC2 SG)
mod3/ec2.tf:   200 lines (EC2, ALB, Route 53, ACM, VPC endpoints)
────────────────────────────
Total:         430 lines across 3 files
```

**Alternative (Single File):**
```
infrastructure.tf:  430 lines (everything together)
```

**Why Separate Modules:**

**1. Clear Dependency Hierarchy**
```
mod1 (VPC)
  ↓ outputs: vpc_id, subnet_ids
mod2 (Security Groups)
  ↓ outputs: alb_sg_id, ec2_sg_id
mod3 (Compute)
  ↓ uses: vpc_id, subnet_ids, sg_ids
```
- **Explicit Dependencies:** Terraform knows mod2 requires mod1 outputs
- **Prevents Circular Refs:** Can't accidentally reference EC2 in VPC module
- **Testable:** Can `terraform plan` mod1 independently

**2. Selective Updates**
```bash
# Only modify security group rules (fast)
cd mod2/
terraform apply

# vs. Re-apply entire infrastructure (slow + risky)
terraform apply  # Touches VPC, ALB, EC2, everything
```
- **Blast Radius Reduction:** Changing SG rule doesn't risk VPC recreation
- **Faster Iterations:** Only rebuild changed module

**3. Reusability**
- `mod1/vpct.tf` reusable across projects (generic VPC setup)
- Different apps can use same VPC module with different variables
- Encourages DRY (Don't Repeat Yourself)

**Trade-off:**
- ❌ More complex (3 `terraform apply` commands vs 1)
- ❌ State management (3 state files vs 1, if not using remote backend)
- ✅ But benefits outweigh for maintainability

### NAT Gateway Disabled by Default

```hcl
# aws/vpc/mod1/vpct.tf:12
variable "enable_nat_gateways" {
  type    = bool
  default = false  # Disabled to save ~$64/month
}
```

**Why Disabled:**

**NAT Gateway Cost:**
```
NAT Gateway in us-east-1a:  $0.045/hour * 730 hours = $32.85/month
NAT Gateway in us-east-1b:  $0.045/hour * 730 hours = $32.85/month
Data processing:            $0.045/GB
────────────────────────────────────────────────────────────────
Total:                      ~$65.70/month + data transfer
```

**VPC S3 Endpoint (Free Alternative):**
```hcl
# aws/vpc/mod3/ec2.tf:151-165
resource "aws_vpc_endpoint" "s3" {
  vpc_id       = var.vpc_id
  service_name = "com.amazonaws.us-east-1.s3"
}
```
- EC2 in private subnet can download JAR from S3 without NAT
- VPC endpoints free for AWS services
- Only handles S3 traffic (not general internet)

**When NAT Gateway Needed:**
```bash
terraform apply -var="enable_nat_gateways=true"
```
- EC2 needs to call external APIs (e.g., third-party services)
- Software updates from public repos (yum, apt)
- Could use VPC endpoints for specific services instead (DynamoDB, SNS, etc.)

**Interview Insight:**
- NAT Gateway is managed service (no EC2 instance to maintain)
- Alternative: NAT Instance (t2.micro with iptables) costs ~$8/month but requires patching
- Production: NAT Gateway preferred for high availability

### Private Subnet for EC2 (Not Public)

```hcl
# aws/vpc/mod3/ec2.tf:24
resource "aws_instance" "jpro_instance" {
  subnet_id = var.private_subnets[0]  # Private subnet (10.0.101.0/24)
  # ...
}
```

**Access Pattern:**
```
Internet → ALB (public subnet) → EC2 (private subnet)
```

**Why Private Subnet:**
- ✅ **Security:** EC2 not directly accessible from internet (no public IP)
- ✅ **Defense in Depth:** Even if EC2 security group misconfigured, no direct route
- ✅ **Best Practice:** Application servers should never be internet-facing

**Trade-off:**
- ❌ **SSH Access:** Can't SSH directly (need bastion host or VPC endpoint)
- ❌ **Debugging:** Harder to `curl` EC2 instance for testing

**Alternatives Considered:**

**1. EC2 in Public Subnet**
```hcl
subnet_id                  = var.public_subnets[0]
map_public_ip_on_launch    = true
```
- ✅ Easy SSH access
- ❌ Security risk (internet exposure)
- ❌ Against AWS Well-Architected Framework

**2. EC2 in Private + Bastion Host**
```
Internet → Bastion (public) → EC2 (private)
```
- ✅ Secure SSH access
- ❌ Extra EC2 cost (~$8/month for t2.micro)
- ❌ Bastion needs hardening (fail2ban, SSM)

**3. EC2 in Private + Systems Manager Session Manager**
```bash
aws ssm start-session --target i-0123456789abcdef0
```
- ✅ No SSH port, no bastion needed
- ✅ Session logs to CloudWatch (auditing)
- ❌ Requires SSM agent on EC2 (installed by default on Amazon Linux 2)

**Current Workaround (Development):**
```hcl
# mod2/sgt.tf:67-73
ingress {
  description = "Allow SSH from allowed CIDR"
  from_port   = 22
  to_port     = 22
  protocol    = "tcp"
  cidr_blocks = [var.allowed_ssh_cidr]  # Default: 0.0.0.0/0 (insecure!)
}
```
- EC2 has SSH rule, but no route from internet (private subnet)
- If NAT enabled + SSH SG rule, can SSH from internet (not recommended)

### ALB in Public Subnet (HTTPS Termination)

```hcl
# aws/vpc/mod3/ec2.tf:34-59
resource "aws_lb" "alb" {
  internal        = false  # Internet-facing
  subnets         = var.public_subnets  # Both AZs
}

resource "aws_lb_listener" "https" {
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = aws_acm_certificate.cert.arn
}
```

**Why ALB (Not NLB or CLB)?**

| Feature | ALB | NLB | CLB |
|---------|-----|-----|-----|
| **Layer** | 7 (HTTP/HTTPS) | 4 (TCP/UDP) | 4 & 7 (legacy) |
| **SSL Termination** | ✅ Native | ⚠️ Via target groups | ✅ Native |
| **WebSocket** | ✅ Supported | ✅ Supported | ❌ Not supported |
| **Sticky Sessions** | ✅ Cookies | ⚠️ IP-based | ✅ Cookies |
| **Cost** | $16/month + LCU | $16/month + LCU | $15/month (legacy) |
| **Best For** | Web apps, microservices | High-throughput TCP, gaming | (Deprecated) |

**ALB Chosen Because:**
- ✅ HTTP/HTTPS routing (path-based, host-based)
- ✅ WebSocket support (JPro uses WebSocket for JavaFX rendering)
- ✅ Sticky sessions (cookie-based, works with multiple EC2 instances)
- ✅ SSL/TLS termination (offloads encryption from EC2)

**Sticky Session Config:**
```hcl
# aws/vpc/mod3/ec2.tf:52-56
stickiness {
  type            = "lb_cookie"
  cookie_duration = 86400  # 24 hours
  enabled         = true
}
```

**Why 24 Hours?**
- JPro session should persist (user shouldn't switch EC2 mid-session)
- If user closes browser and returns same day, reconnects to same instance
- Could reduce to 1 hour (3600s) for faster load distribution

**Why Sticky Sessions?**
- JPro maintains server-side state (JavaFX scenegraph)
- Each user must connect to same EC2 instance for entire session
- If routed to different instance, JavaFX state lost (blank screen)

### Route 53 + ACM for HTTPS

```hcl
# aws/vpc/mod3/ec2.tf:109-149
resource "aws_route53_zone" "main" {
  name = "tttlexc24.it.com"
}

resource "aws_acm_certificate" "cert" {
  domain_name       = "api.tttlexc24.it.com"
  validation_method = "DNS"
}

resource "aws_route53_record" "cert_validation" {
  # Automatically creates CNAME record for ACM validation
}

resource "aws_route53_record" "api" {
  name = "api.tttlexc24.it.com"
  type = "A"
  alias {
    name    = aws_lb.alb.dns_name
    zone_id = aws_lb.alb.zone_id
  }
}
```

**Why DNS Validation (Not Email)?**

**DNS Validation:**
- Terraform creates validation record automatically
- ACM verifies ownership by checking CNAME
- Auto-renewal works seamlessly (no manual action)

**Email Validation:**
- AWS sends email to `admin@tttlexc24.it.com`
- Human must click link (breaks automation)
- Renewal requires manual action (risky)

**Why Alias Record (Not CNAME)?**

**A Record + Alias:**
```
api.tttlexc24.it.com → ALB (ttt-alb-123456789.us-east-1.elb.amazonaws.com)
```
- ✅ Free (Route 53 doesn't charge for alias queries)
- ✅ Auto-updates if ALB DNS changes
- ✅ Works at zone apex (e.g., `tttlexc24.it.com` could point to ALB)

**CNAME:**
```
api.tttlexc24.it.com CNAME ttt-alb-123456789.us-east-1.elb.amazonaws.com
```
- ❌ Charged ($0.40 per million queries)
- ❌ Cannot use at zone apex (RFC 1912 restriction)

### Terraform + CloudFormation Hybrid

**Why CloudFormation for DynamoDB?**

**CloudFormation Template:**
```yaml
# aws/template.yaml:5-46
Resources:
  TicTacToeUsersTable:
    Type: AWS::DynamoDB::Table
    Properties:
      TableName: TicTacToeUsers
      BillingMode: PAY_PER_REQUEST
      StreamSpecification:
        StreamViewType: NEW_AND_OLD_IMAGES
      GlobalSecondaryIndexes:
        - IndexName: statusIndex
          KeySchema:
            - AttributeName: status
              KeyType: HASH
            - AttributeName: joinedAt
              KeyType: RANGE
```

**Alternative (Terraform):**
```hcl
resource "aws_dynamodb_table" "users" {
  name           = "TicTacToeUsers"
  billing_mode   = "PAY_PER_REQUEST"

  stream_enabled   = true
  stream_view_type = "NEW_AND_OLD_IMAGES"

  global_secondary_index {
    name            = "statusIndex"
    hash_key        = "status"
    range_key       = "joinedAt"
    projection_type = "ALL"
  }
}
```

**Why CloudFormation Chosen:**
- **Hypothetical Reason 1:** Legacy template existed before Terraform adoption
- **Hypothetical Reason 2:** Team more familiar with CFN for DynamoDB
- **Hypothetical Reason 3:** AWS SAM CLI integration (local DynamoDB testing)

**Production Recommendation:**
- ✅ **Consolidate to Terraform:** Use `aws_dynamodb_table` resource (consistency)
- ❌ **Keep Hybrid:** Adds tool switching overhead

**When Hybrid Makes Sense:**
- Some resources managed by AWS SAM (Lambda + API Gateway)
- DynamoDB part of SAM template for local testing (`sam local start-api`)
- Terraform manages long-lived infrastructure (VPC, EC2)

## Integration Impact

### Module Dependencies (Variable Passing)

**mod1 outputs → mod2 inputs:**
```hcl
# mod1/vpct.tf outputs
output "vpc_id" {
  value = aws_vpc.vpc.id
}

# mod2/sgt.tf inputs
variable "vpc_id" {
  type = string
}

# Deployment command
terraform apply -var="vpc_id=vpc-0123456789abcdef0"
```

**Why Not Terraform Remote State?**

**Remote State Approach:**
```hcl
# mod2/sgt.tf
data "terraform_remote_state" "vpc" {
  backend = "s3"
  config = {
    bucket = "terraform-state-bucket"
    key    = "mod1/terraform.tfstate"
  }
}

resource "aws_security_group" "alb_sg" {
  vpc_id = data.terraform_remote_state.vpc.outputs.vpc_id
}
```

**Benefits:**
- ✅ Automatic dependency tracking (no manual `-var` passing)
- ✅ State sharing across teams
- ✅ Prevents drift (single source of truth)

**Why Not Used Here:**
- Current approach uses manual variable passing (simpler for single developer)
- Production should use remote state (S3 + DynamoDB locking)

### User Data Script (Deployment Automation)

```bash
# aws/vpc/mod3/user_data.sh:1-50
#!/bin/bash

# Install dependencies
sudo yum install -y java-17-amazon-corretto-headless nginx git aws-cli

# Download JAR from S3
aws s3 cp s3://tttbucket-lexc24/TTTG.jar /home/ec2-user/TTTG.jar

# Create systemd service
sudo bash -c 'cat > /etc/systemd/system/jpro.service <<EOF
[Service]
ExecStart=/usr/bin/java -jar /home/ec2-user/TTTG.jar
Restart=always
User=ec2-user
Environment=JPRO_PORT=8080
EOF'

# Start services
sudo systemctl enable jpro nginx
sudo systemctl start jpro nginx
```

**Why User Data (Not AMI)?**

**User Data (Current):**
- ✅ **Simple:** Bash script in Terraform (no Packer needed)
- ✅ **Flexible:** Change JAR location by editing script
- ❌ **Slow:** ~3-5 minutes to download/install on boot

**Custom AMI (Alternative):**
- ✅ **Fast:** Pre-install Java, JAR, config (30-second boot)
- ❌ **Complex:** Need Packer build pipeline
- ❌ **Stale:** If JAR updates, must rebuild AMI

**When AMI Better:**
- Auto Scaling Group (frequent instance launches)
- Immutable infrastructure (never update, only replace)
- Compliance (audited base image)

**Why Nginx (Not Direct JPro)?**

**Current: ALB → Nginx (port 80) → JPro (port 8080)**
- ✅ Reverse proxy pattern (separation of concerns)
- ✅ Can add rate limiting, caching, compression
- ✅ Nginx handles CORS headers

**Alternative: ALB → JPro (port 8080)**
- ✅ Simpler (one less component)
- ❌ JPro handles HTTP directly (less battle-tested than Nginx)
- ❌ No easy way to add middleware (auth, logging)

## Alternative Approaches Considered

### 1. AWS CDK (TypeScript/Python)

**Implementation:**
```typescript
// lib/tictactoe-stack.ts
const vpc = new ec2.Vpc(this, 'VPC', {
  maxAzs: 2,
  natGateways: 0
});

const alb = new elbv2.ApplicationLoadBalancer(this, 'ALB', {
  vpc,
  internetFacing: true
});

const instance = new ec2.Instance(this, 'JPro', {
  vpc,
  instanceType: ec2.InstanceType.of(ec2.InstanceClass.T2, ec2.InstanceSize.MICRO),
  machineImage: ec2.MachineImage.latestAmazonLinux2()
});
```

**Rejected Because:**
- ❌ **Opinionated:** CDK makes assumptions (e.g., default subnets, routes)
- ❌ **Debugging:** Synthesizes to CloudFormation (hard to troubleshoot)
- ❌ **Learning Curve:** Need TypeScript/Python knowledge + CDK constructs
- ✅ **When Better:** Large teams, rapid prototyping, strong typing benefits

### 2. Pulumi (Multi-Language IaC)

**Implementation:**
```python
# __main__.py
import pulumi
import pulumi_aws as aws

vpc = aws.ec2.Vpc("vpc", cidr_block="10.0.0.0/16")
alb = aws.lb.LoadBalancer("alb", subnets=[subnet.id for subnet in subnets])
```

**Rejected Because:**
- ❌ **Maturity:** Smaller community than Terraform
- ❌ **Provider Lag:** AWS updates slower than Terraform
- ✅ **When Better:** Using programming language features (loops, conditionals)

### 3. Ansible (Configuration Management)

**Rejected Because:**
- ❌ **Not IaC:** Ansible for configuration, not provisioning
- ❌ **State Management:** No native state tracking (would need custom logic)
- ✅ **When Better:** Configuring existing servers (not creating VPCs)

### 4. Manual AWS Console

**Rejected Because:**
- ❌ **Not Reproducible:** Can't recreate in new account
- ❌ **No Version Control:** Can't track changes
- ❌ **Slow:** Point-and-click vs code

## Trade-Offs Accepted

### ❌ Manual Module Ordering

**Current Deployment:**
```bash
cd aws/vpc/mod1 && terraform apply
cd ../mod2 && terraform apply -var="vpc_id=vpc-xxx"
cd ../mod3 && terraform apply -var="vpc_id=vpc-xxx" -var="alb_sg_id=sg-xxx"
```

**Better (Terragrunt):**
```hcl
# terragrunt.hcl
dependencies {
  paths = ["../mod1", "../mod2"]
}

# Deployment
terragrunt apply  # Auto-detects dependencies
```

**Why Not Used:**
- Extra tool dependency (Terraform + Terragrunt)
- Overkill for 3 modules
- Production should use Terragrunt or Terraform workspaces

### ❌ No Terraform Backend (Local State)

**Current:**
```
mod1/terraform.tfstate  # Local file (not committed)
mod2/terraform.tfstate
mod3/terraform.tfstate
```

**Production (S3 Backend):**
```hcl
# backend.tf
terraform {
  backend "s3" {
    bucket         = "tictactoe-terraform-state"
    key            = "mod1/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "terraform-locks"
  }
}
```

**Benefits:**
- ✅ Team collaboration (shared state)
- ✅ State locking (prevents concurrent apply)
- ✅ Versioning (S3 bucket versioning)

**Why Not Implemented:**
- Solo developer (no concurrent access)
- Demo project (not production critical)

### ❌ Hardcoded Values (Not Variables)

**Examples:**
```hcl
# Should be variable
domain_name = "tttlexc24.it.com"
bucket_name = "tttbucket-lexc24"
region      = "us-east-1"
```

**Better:**
```hcl
variable "domain_name" {
  type = string
}

variable "region" {
  type    = string
  default = "us-east-1"
}
```

**Why Hardcoded:**
- Single deployment (not multi-environment)
- Reduces cognitive load (fewer variables to pass)
- Production should parameterize everything

## Interview-Worthy Insights

### Why VPC Flow Logs?

```hcl
# aws/vpc/mod1/vpct.tf:123-139
resource "aws_flow_log" "vpc_flow_log" {
  traffic_type = "ALL"  # Accepted + rejected
  vpc_id       = aws_vpc.vpc.id
}
```

**Use Cases:**
- **Security:** Detect port scans, unauthorized access attempts
- **Debugging:** Why can't EC2 reach DynamoDB? (flow logs show rejected traffic)
- **Compliance:** PCI-DSS requires network traffic logging

**Cost:**
- $0.50 per GB ingested to CloudWatch Logs
- Typical VPC: ~1-5 GB/day = ~$15-75/month

**Alternative:**
- Flow logs to S3 (cheaper: $0.023 per GB stored)
- Enable only for troubleshooting (not always-on)

### How ALB Health Checks Work

```hcl
# aws/vpc/mod3/ec2.tf:46-51
health_check {
  path                = "/"
  interval            = 30
  healthy_threshold   = 2
  unhealthy_threshold = 2
}
```

**Behavior:**
```
t=0s:  ALB sends GET / to EC2
t=0s:  EC2 responds 200 OK (healthy: 1/2)
t=30s: ALB sends GET / to EC2
t=30s: EC2 responds 200 OK (healthy: 2/2) → MARKED HEALTHY
```

**If Failure:**
```
t=60s: ALB sends GET / to EC2
t=60s: EC2 timeout (unhealthy: 1/2)
t=90s: ALB sends GET / to EC2
t=90s: EC2 timeout (unhealthy: 2/2) → MARKED UNHEALTHY
```

**Why 2 Thresholds (Not 1)?**
- Prevents flapping (transient network issues don't remove instance)
- Balance: 1 = too sensitive, 5 = too slow to detect failures

### Terraform vs CloudFormation Feature Parity

**Terraform Advantages:**
- ✅ Multi-cloud (AWS, Azure, GCP with same syntax)
- ✅ Better state management (plan/apply separation)
- ✅ Module ecosystem (Terraform Registry)

**CloudFormation Advantages:**
- ✅ Native AWS integration (no API rate limits)
- ✅ Rollback on failure (automatic stack rollback)
- ✅ Drift detection (shows manual console changes)

**When to Use Each:**
- **Terraform:** Multi-cloud, complex logic, active community
- **CloudFormation:** AWS-only, SAM integration, enterprise compliance

## Code References

- **VPC Module:** `aws/vpc/mod1/vpct.tf:1-150`
- **Security Groups:** `aws/vpc/mod2/sgt.tf:1-80`
- **Compute & Load Balancing:** `aws/vpc/mod3/ec2.tf:1-200`
- **DynamoDB Table:** `aws/template.yaml:5-46`
- **User Data Script:** `aws/vpc/mod3/user_data.sh:1-50`

## Related ADRs

- **ADR-001:** WebSocket vs REST API (why Lambda-based backend, not EC2-only)
- **ADR-002:** DynamoDB Streams (why DynamoDB over RDS)

## Future Considerations

If moving to production:
1. **Remote State:** S3 + DynamoDB locking
2. **Workspaces:** Separate dev/staging/prod environments
3. **CI/CD:** Terraform Cloud or GitLab CI for automated apply
4. **Auto Scaling:** Replace single EC2 with Auto Scaling Group
5. **Multi-Region:** Route 53 latency routing, DynamoDB global tables
