# Terraform: Security Groups for Tic-Tac-Toe VPC
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
variable "region" {
  description = "AWS region to deploy resources"
  type        = string
  default     = "us-east-1"
}

variable "vpc_id" {
  description = "The ID of the VPC to attach security groups to"
  type        = string
}

variable "allowed_ssh_cidr" {
  description = "CIDR block allowed SSH access (for example, your office IP)"
  type        = string
  default     = "0.0.0.0/0"
}

# Security Group for the Application Load Balancer
resource "aws_security_group" "alb" {
  name        = "ttt-alb-sg"
  description = "Allow HTTPS (and HTTP health checks) to ALB"
  vpc_id      = var.vpc_id

  # HTTPS from anywhere
  ingress {
    description = "Allow HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # HTTP health check
  ingress {
    description = "Allow HTTP for health checks"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # All outbound
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "ttt-alb-sg"
  }
}

# Security Group for the JPro/Nginx EC2 Instances
resource "aws_security_group" "jpro" {
  name        = "ttt-jpro-sg"
  description = "Allow ALB traffic and SSH into JPro server"
  vpc_id      = var.vpc_id

  # Allow HTTP from ALB SG
  ingress {
    description     = "Allow HTTP from ALB"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # SSH access (restricted CIDR)
  ingress {
    description = "SSH access"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.allowed_ssh_cidr]
  }

  # Allow outbound
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "ttt-jpro-sg"
  }
}

# Outputs for downstream modules
output "alb_security_group_id" {
  description = "Security group ID for the Application Load Balancer"
  value       = aws_security_group.alb.id
}

output "jpro_security_group_id" {
  description = "Security group ID for the JPro/Nginx EC2 instances"
  value       = aws_security_group.jpro.id
}
