# Terraform: EC2 Instance and Application Load Balancer for Tic-Tac-Toe
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"  
    }
  }

  required_version = ">= 1.2.0"
}

provider "aws" {
  region = var.region
}

# 1. EC2-assumeable role
data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "jpro_ec2_role" {
  name               = "ttt-jpro-ec2-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

# 2. Attach AmazonS3ReadOnlyAccess so your user_data s3 cp works
resource "aws_iam_role_policy_attachment" "jpro_s3_read" {
  role       = aws_iam_role.jpro_ec2_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess"
}

# 3. Expose that role via an instance profile
resource "aws_iam_instance_profile" "jpro_profile" {
  name = "ttt-jpro-instance-profile"
  role = aws_iam_role.jpro_ec2_role.name
}

############################################################
# EC2 Instance: JPro + Nginx
############################################################
resource "aws_instance" "jpro_server" {
  ami                    = var.ami_id
  instance_type          = var.instance_type
  subnet_id              = var.private_subnets[0]
  vpc_security_group_ids = [var.jpro_security_group_id]
  key_name               = var.key_name

  # No public IP: managed via ALB
  associate_public_ip_address = false
  iam_instance_profile        = aws_iam_instance_profile.jpro_profile.name

  tags = {
    Name = "ttt-jpro-server"
  }

  # User Data: install dependencies and deploy application
  user_data = file("${path.module}/user_data.sh")
}

############################################################
# Application Load Balancer
############################################################
resource "aws_lb" "ttt_alb" {
  name               = "ttt-alb"
  load_balancer_type = "application"
  subnets            = var.public_subnets
  security_groups    = [var.alb_security_group_id]

  # Optional: adjust idle timeout for WebSocket connections
  idle_timeout               = 600 # 10 mins
  enable_deletion_protection = false

  tags = {
    Name = "ttt-alb"
  }
}

############################################################
# Target Group for JPro
############################################################
resource "aws_lb_target_group" "jpro_tg" {
  name     = "ttt-jpro-tg"
  port     = 80
  protocol = "HTTP"
  vpc_id   = var.vpc_id

  health_check {
    path                = "/health"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # Sticky sessions for JPro WebSocket connections
  stickiness {
    type            = "lb_cookie"
    cookie_duration = 86400
    enabled         = true
  }

  tags = {
    Name = "ttt-jpro-tg"
  }
}

############################################################
# ALB Listeners
############################################################

# HTTP -> HTTPS redirect
resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.ttt_alb.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      protocol    = "HTTPS"
      port        = "443"
      status_code = "HTTP_301"
    }
  }
}

############################################################
# Attach EC2 Instance to Target Group
############################################################
resource "aws_lb_target_group_attachment" "jpro_attach" {
  target_group_arn = aws_lb_target_group.jpro_tg.arn
  target_id        = aws_instance.jpro_server.id
  port             = 80
}

############################################################
# VPC Endpoint for S3 (for private subnet access)
############################################################
resource "aws_vpc_endpoint" "s3" {
  vpc_id          = var.vpc_id
  service_name    = "com.amazonaws.${var.region}.s3"
  route_table_ids = var.private_route_table_ids
}

############################################################
# Route 53 Hosted Zone
############################################################
resource "aws_route53_zone" "tttlexc24" {
  name = "tttlexc24.it.com"

  tags = {
    Name = "tttlexc24-hosted-zone"
  }
}

# API subdomain pointing to ALB
resource "aws_route53_record" "api_alias" {
  zone_id = aws_route53_zone.tttlexc24.zone_id
  name    = "api" # → api.tttlexc24.it.com
  type    = "A"

  alias {
    name                   = aws_lb.ttt_alb.dns_name
    zone_id                = aws_lb.ttt_alb.zone_id
    evaluate_target_health = true
  }
}

############################################################
# ACM Certificate for api.tttlexc24.it.com
############################################################
resource "aws_acm_certificate" "api_cert" {
  domain_name       = "api.tttlexc24.it.com"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "api-tttlexc24-cert"
  }
}

# DNS validation records
resource "aws_route53_record" "api_cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.api_cert.domain_validation_options :
    dvo.domain_name => {
      name   = dvo.resource_record_name
      type   = dvo.resource_record_type
      record = dvo.resource_record_value
    }
  }

  zone_id = aws_route53_zone.tttlexc24.zone_id
  name    = each.value.name
  type    = each.value.type
  ttl     = 300
  records = [each.value.record]
}

# Wait for certificate validation
resource "aws_acm_certificate_validation" "api_cert_validation" {
  certificate_arn         = aws_acm_certificate.api_cert.arn
  validation_record_fqdns = [for rec in aws_route53_record.api_cert_validation : rec.fqdn]

  timeouts {
    create = "10m"
  }
}

############################################################
# HTTPS Listener with TLS Certificate
############################################################
resource "aws_lb_listener" "https_listener" {
  load_balancer_arn = aws_lb.ttt_alb.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06" # Modern SSL policy
  certificate_arn   = aws_acm_certificate_validation.api_cert_validation.certificate_arn

  default_action {
    type = "forward"

    forward {
      target_group {
        arn = aws_lb_target_group.jpro_tg.arn
      }
    }
  }
}

############################################################
# Variables
############################################################
variable "region" {
  description = "AWS region to deploy resources"
  type        = string
  default     = "us-east-1"
}

variable "private_route_table_ids" {
  description = "Route table IDs for private subnets"
  type        = list(string)
}

variable "vpc_id" {
  description = "The VPC where resources will be deployed"
  type        = string
}

variable "public_subnets" {
  description = "List of public subnet IDs for the ALB"
  type        = list(string)
}

variable "private_subnets" {
  description = "List of private subnet IDs for the JPro EC2 instance"
  type        = list(string)
}

variable "alb_security_group_id" {
  description = "Security group ID for the ALB"
  type        = string
}

variable "jpro_security_group_id" {
  description = "Security group ID for the JPro EC2 instance"
  type        = string
}

variable "ami_id" {
  description = "AMI ID for the EC2 instance (e.g., Amazon Linux 2)"
  type        = string
}

variable "instance_type" {
  description = "EC2 instance type (e.g., t3.small)"
  type        = string
}

variable "key_name" {
  description = "Name of the SSH key pair to attach to the instance"
  type        = string
}

############################################################
# Outputs
############################################################
output "alb_dns_name" {
  description = "DNS name of the Application Load Balancer"
  value       = aws_lb.ttt_alb.dns_name
}

output "api_domain" {
  description = "API domain for frontend to use"
  value       = "https://api.tttlexc24.it.com"
}

output "jpro_instance_id" {
  description = "ID of the JPro EC2 instance"
  value       = aws_instance.jpro_server.id
}

output "route53_nameservers" {
  description = "Route 53 nameservers - add these to your domain registrar"
  value       = aws_route53_zone.tttlexc24.name_servers
}

output "certificate_arn" {
  description = "ARN of the validated SSL certificate"
  value       = aws_acm_certificate_validation.api_cert_validation.certificate_arn
}