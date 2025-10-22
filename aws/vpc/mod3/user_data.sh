#!/bin/bash

# Enable error handling
set -e

# Log all output
exec > >(tee /var/log/user-data.log)
exec 2>&1

echo "Starting user data script at $(date)"

# Update system packages
echo "Updating system packages..."
yum update -y

# Install required packages including AWS CLI
echo "Installing required packages..."
yum install -y java-17-openjdk-devel git nginx awscli

# Verify Java installation
echo "Java version:"
java -version

# Create application directory
echo "Creating application directory..."
mkdir -p /opt/tttg
cd /opt/tttg

# Download JAR from S3 with error handling
echo "Downloading JAR from S3..."
if aws s3 cp s3://tttbucket-lexc24/TTTG.jar ./TTTG.jar; then
    echo "JAR downloaded successfully"
    ls -la /opt/tttg/
else
    echo "ERROR: Failed to download JAR from S3"
    exit 1
fi

# Create systemd service file
echo "Creating systemd service..."
cat > /etc/systemd/system/tttg.service << 'EOF'
[Unit]
Description=TTTG JPro Application
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/opt/tttg
ExecStart=/usr/bin/java -jar /opt/tttg/TTTG.jar
Restart=always
RestartSec=10
Environment=JAVA_HOME=/usr/lib/jvm/java-17-openjdk
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# Configure Nginx reverse proxy
echo "Configuring Nginx..."
cat > /etc/nginx/conf.d/tttg.conf << 'EOF'
upstream jpro {
    server 127.0.0.1:8080;
}

server {
    listen 80;
    server_name _;
    
    # Health check endpoint for ALB
    location /health {
        access_log off;
        return 200 "healthy\n";
        add_header Content-Type text/plain;
    }
    
    # Proxy to JPro application
    location / {
        proxy_pass http://jpro;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Host $http_host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 86400;
        proxy_http_version 1.1;
        
        # CORS headers for your frontend domain
        add_header 'Access-Control-Allow-Origin' 'https://tttlexc24.it.com' always;
        add_header 'Access-Control-Allow-Methods' 'GET, POST, PUT, DELETE, OPTIONS' always;
        add_header 'Access-Control-Allow-Headers' 'DNT,User-Agent,X-Requested-With,If-Modified-Since,Cache-Control,Content-Type,Range,Authorization' always;
        add_header 'Access-Control-Expose-Headers' 'Content-Length,Content-Range' always;
        
        # Handle preflight requests
        if ($request_method = 'OPTIONS') {
            add_header 'Access-Control-Allow-Origin' 'https://tttlexc24.it.com';
            add_header 'Access-Control-Allow-Methods' 'GET, POST, PUT, DELETE, OPTIONS';
            add_header 'Access-Control-Allow-Headers' 'DNT,User-Agent,X-Requested-With,If-Modified-Since,Cache-Control,Content-Type,Range,Authorization';
            add_header 'Access-Control-Max-Age' 1728000;
            add_header 'Content-Type' 'text/plain; charset=utf-8';
            add_header 'Content-Length' 0;
            return 204;
        }
    }
}
EOF

# Set proper permissions
echo "Setting proper permissions..."
chown -R ec2-user:ec2-user /opt/tttg
chmod +x /opt/tttg/TTTG.jar

# Start and enable services
echo "Starting services..."
systemctl daemon-reload

# Start nginx first
if systemctl enable nginx && systemctl start nginx; then
    echo "Nginx started successfully"
else
    echo "ERROR: Failed to start Nginx"
    exit 1
fi

# Wait a moment for nginx to fully start
sleep 5

# Start tttg service
if systemctl enable tttg && systemctl start tttg; then
    echo "TTTG service started successfully"
else
    echo "ERROR: Failed to start TTTG service"
    exit 1
fi

# Verify services are running
echo "Checking service status..."
systemctl status nginx --no-pager
systemctl status tttg --no-pager

echo "User data script completed successfully at $(date)"