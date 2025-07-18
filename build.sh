# build.sh
#!/bin/bash

echo "Building Docker image"
docker build -f Dockerfile -t gasparbarancelli/rinha-de-backend-2025:latest .

echo "Build completed!"