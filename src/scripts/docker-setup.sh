docker pull minnced/strumbot:%VERSION%
docker run -d --restart unless-stopped -v "$(pwd)/config.json:/etc/strumbot/config.json" --name strumbot minnced/strumbot:%VERSION%
