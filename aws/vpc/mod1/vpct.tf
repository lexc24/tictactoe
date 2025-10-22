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


# ---------------- VPC Creation ---------------- #
resource "aws_vpc" "ttt_vpc" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true
  
  tags = {
    Name = "ttt-vpc"
  }
}

# ---------------- Internet Gateway ---------------- # 
resource "aws_internet_gateway" "igw" {
  vpc_id = aws_vpc.ttt_vpc.id
  
  tags = {
    Name = "ttt-igw"
  }
}

# ---------------- Subnets ---------------- #
# Public Subnet A
resource "aws_subnet" "public_subnet_a" {
  vpc_id                  = aws_vpc.ttt_vpc.id
  cidr_block              = var.public_subnet_a_cidr
  availability_zone       = "${var.region}a"
  map_public_ip_on_launch = true
  
  tags = {
    Name = "ttt-public-subnet-a"
  }
}

# Private Subnet A
resource "aws_subnet" "private_subnet_a" {
  vpc_id                  = aws_vpc.ttt_vpc.id
  cidr_block              = var.private_subnet_a_cidr
  availability_zone       = "${var.region}a"
  map_public_ip_on_launch = false
  
  tags = {
    Name = "ttt-private-subnet-a"
  }
}

# Public Subnet B
resource "aws_subnet" "public_subnet_b" {
  vpc_id                  = aws_vpc.ttt_vpc.id
  cidr_block              = var.public_subnet_b_cidr
  availability_zone       = "${var.region}b"
  map_public_ip_on_launch = true
  
  tags = {
    Name = "ttt-public-subnet-b"
  }
}

# Private Subnet B
resource "aws_subnet" "private_subnet_b" {
  vpc_id                  = aws_vpc.ttt_vpc.id
  cidr_block              = var.private_subnet_b_cidr
  availability_zone       = "${var.region}b"
  map_public_ip_on_launch = false
  
  tags = {
    Name = "ttt-private-subnet-b"
  }
}

#---------------- EIP & NAT Gateways ----------------#
# Only created when enable_nat_gateways = true

# NAT Gateway A
resource "aws_eip" "nat_eip_a" {
  count  = var.enable_nat_gateways ? 1 : 0
  domain = "vpc"
  tags = { 
    Name = "ttt-nat-eip-a" 
  }
}

resource "aws_nat_gateway" "nat_gateway_a" {
  count         = var.enable_nat_gateways ? 1 : 0
  allocation_id = aws_eip.nat_eip_a[0].id
  subnet_id     = aws_subnet.public_subnet_a.id
  tags = { 
    Name = "ttt-nat-gateway-a" 
  }
}

# NAT Gateway B
resource "aws_eip" "nat_eip_b" {
  count  = var.enable_nat_gateways ? 1 : 0
  domain = "vpc"
  tags = { 
    Name = "ttt-nat-eip-b" 
  }
}

resource "aws_nat_gateway" "nat_gateway_b" {
  count         = var.enable_nat_gateways ? 1 : 0
  allocation_id = aws_eip.nat_eip_b[0].id
  subnet_id     = aws_subnet.public_subnet_b.id
  tags = { 
    Name = "ttt-nat-gateway-b" 
  }
}

# ---------------- Route Tables ---------------- #
# Public Route Table
resource "aws_route_table" "public_rt" {
  vpc_id = aws_vpc.ttt_vpc.id
  
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.igw.id
  }
  
  tags = {
    Name = "ttt-public-rt"
  }
}

# Private RT for AZ A - Conditional NAT Gateway
resource "aws_route_table" "private_rt_a" {
  vpc_id = aws_vpc.ttt_vpc.id
  
  dynamic "route" {
    for_each = var.enable_nat_gateways ? [1] : []
    content {
      cidr_block     = "0.0.0.0/0"
      nat_gateway_id = aws_nat_gateway.nat_gateway_a[0].id
    }
  }
  
  tags = { Name = "ttt-private-rt-a" }
}

# Private RT for AZ B - Conditional NAT Gateway
resource "aws_route_table" "private_rt_b" {
  vpc_id = aws_vpc.ttt_vpc.id
  
  dynamic "route" {
    for_each = var.enable_nat_gateways ? [1] : []
    content {
      cidr_block     = "0.0.0.0/0"
      nat_gateway_id = aws_nat_gateway.nat_gateway_b[0].id
    }
  }
  
  tags = { Name = "ttt-private-rt-b" }
}

# ---------------- Route Table Associations ---------------- #
# Public Subnet A Association
resource "aws_route_table_association" "public_a_rt_assoc" { 
  subnet_id = aws_subnet.public_subnet_a.id 
  route_table_id = aws_route_table.public_rt.id 
}

# Public Subnet B Association
resource "aws_route_table_association" "public_b_rt_assoc" { 
  subnet_id = aws_subnet.public_subnet_b.id 
  route_table_id = aws_route_table.public_rt.id 
}

# Private Subnet A Association
resource "aws_route_table_association" "private_a_rt_assoc" {
  subnet_id      = aws_subnet.private_subnet_a.id
  route_table_id = aws_route_table.private_rt_a.id
}

# Private Subnet B Association
resource "aws_route_table_association" "private_b_rt_assoc" {
  subnet_id      = aws_subnet.private_subnet_b.id
  route_table_id = aws_route_table.private_rt_b.id
}

# ---------------- VPC Flow Logs ---------------- #
resource "aws_cloudwatch_log_group" "vpc_flow_logs" {
  name              = "/aws/vpc/ttt-vpc"
  retention_in_days = 30
}

resource "aws_flow_log" "vpc_flow_logs" {
  vpc_id               = aws_vpc.ttt_vpc.id
  traffic_type         = "ALL"
  log_destination_type = "cloud-watch-logs"
  log_destination      = aws_cloudwatch_log_group.vpc_flow_logs.arn
}

# ---------------- Variables ---------------- #
variable "region" {
  description = "AWS region to deploy resources"
  type        = string
  default     = "us-east-1"
}

variable "vpc_cidr" {
  description = "CIDR block for VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_a_cidr" {
  description = "CIDR block for public subnet in AZ A"
  type        = string
  default     = "10.0.1.0/24"
}

variable "private_subnet_a_cidr" {
  description = "CIDR block for private subnet in AZ A"
  type        = string
  default     = "10.0.2.0/24"
}

variable "public_subnet_b_cidr" {
  description = "CIDR block for public subnet in AZ B"
  type        = string
  default     = "10.0.3.0/24"
}

variable "private_subnet_b_cidr" {
  description = "CIDR block for private subnet in AZ B"
  type        = string
  default     = "10.0.4.0/24"
}

variable "enable_nat_gateways" {
  description = "Whether to create NAT Gateways (incurs cost even when not in use)"
  type        = bool
  default     = false
}

#---------------- Outputs ---------------- #
output "vpc_id" {
  description = "ID of the VPC"
  value       = aws_vpc.ttt_vpc.id
}

output "public_subnet_a_id" {
  description = "ID of public subnet A"
  value       = aws_subnet.public_subnet_a.id
}

output "public_subnet_b_id" {
  description = "ID of public subnet B"
  value       = aws_subnet.public_subnet_b.id
}

output "private_subnet_a_id" {
  description = "ID of private subnet A"
  value = aws_subnet.private_subnet_a.id
}

output "private_subnet_b_id" {
  description = "ID of private subnet B"
  value = aws_subnet.private_subnet_b.id
}

output "nat_gateway_a_id" { 
  description = "ID of NAT Gateway A"
  value = var.enable_nat_gateways ? aws_nat_gateway.nat_gateway_a[0].id : "NAT Gateway A not enabled"
}

output "nat_gateway_b_id" { 
  description = "ID of NAT Gateway B"
  value = var.enable_nat_gateways ? aws_nat_gateway.nat_gateway_b[0].id : "NAT Gateway B not enabled"
}