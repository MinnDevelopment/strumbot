[live-event]: https://raw.githubusercontent.com/MinnDevelopment/strumbot/master/assets/readme/live-event.png
[update-event]: https://raw.githubusercontent.com/MinnDevelopment/strumbot/master/assets/readme/update-event.png
[vod-event]: https://raw.githubusercontent.com/MinnDevelopment/strumbot/master/assets/readme/vod-event.png
[example-config]: https://github.com/MinnDevelopment/strumbot/blob/master/example-config.json

[ ![docker-pulls](https://img.shields.io/docker/pulls/minnced/strumbot) ](https://hub.docker.com/repository/docker/minnced/strumbot)
[ ![release](https://img.shields.io/github/v/tag/minndevelopment/strumbot) ](https://github.com/MinnDevelopment/strumbot/releases/latest)

# Strumbot

A Twitch Stream Notification Bot. This will send notifications to a webhook in your Discord server when the subscribed streamer goes live or changes their game.

## Requirements

- JDK 1.8 or better
- Stable Internet

## Configurations

The configuration file must be called `config.json` and has to be in the working directory. An example configuration can be found in [`example-config.json`][example-config].
Anything marked with **(optional)** can be set to `null` to be disabled.

### Discord

This section of the configuration contains settings for the discord side of the bot such as role names and webhook URLs.
Note that the bot uses global role cache, independent of servers, and it is recommended to only have the bot account in one server.

If you don't know how to create a discord bot and get access to the token: [How to make a discord bot](https://github.com/MinnDevelopment/strumbot/blob/master/guides/HOW_TO_CREATE_A_BOT.md)

- `token` The discord bot token
- `stream_notifications` The webhook URL to send stream updates to
- `message_logs` **(optional)** The webhook URL for message logs (edits/deletes)
- `role_name` Configuration of `type`->`role` to change the default names of the update roles
- `enabled_events` Array of events to publish to the `stream_notifications` webhook

The roles used for updates can be managed by the bot with the `?rank <type>` command.
This command will automatically assign the role to the user.

For example, with the configuration `"live": "Stream is Live"` the bot will accept the command `?rank live` and assign/remove the role `Stream is Live` for the user.
The bot will automatically delete its own message if the invoking message is deleted by the user or an admin.

#### Events

- [`live`][live-event] When the streamer goes live
- [`update`][update-event] When the streamer changes the current game
- [`vod`][vod-event] When the streamer goes offline (includes vod timestamps for game changes)

### Twitch

This configuration section contains required information to track the stream status.

If you don't know how to make a twitch application and access the client_id: [How to make a twitch app](https://github.com/MinnDevelopment/strumbot/blob/master/guides/HOW_TO_CREATE_A_TWITCH_APP.md)

- `client_id` The twitch application's client_id
- `client_secret` The twitch application's client_secret
- `user_login` The username of the tracked streamer

### Example

```json
{
  "discord": {
    "token": "NjUzMjM1MjY5MzM1NjQ2MjA4.*******.*******",
    "stream_notifications": "https://discordapp.com/api/webhooks/*******/******",
    "message_logs": null,
    "role_name": {
      "live": "live",
      "vod": "vod",
      "update": "update"
    },
    "enabled_events": ["live", "update", "vod"]
  },
  "twitch": {
    "client_id": "*******",
    "client_secret": "*******",
    "user_login": ["Elajjaz", "Distortion2"]
  }
}
```

## Setup

Currently I only provide 2 setups. Either [docker](https://hub.docker.com) or through a script.
I'm open for pull requests that introduce more or better setups.

### Docker

The image is hosted at [docker hub](https://hub.docker.com/repository/docker/minnced/strumbot).

1. Open a terminal in the directory of your choice (which includes the `config.json`!)
1. Pull the image with `docker pull minnced/strumbot:%VERSION%` (Replace `%VERSION%` with the version here: [latest release](https://github.com/MinnDevelopment/strumbot/releases/latest))
1. Change the configuration in `config.json`
1. Create and start a container with this command:
    ```sh
    docker run -d \
      -v $(pwd)/config.json:/etc/strumbot/config.json \
      --name strumbot \
      --restart unless-stopped \
      minnced/strumbot:%VERSION%
   ```

> For windows users: Replace `$(pwd)` with `%cd%` and `\` with `^`!

### Script

1. Download the zip archive from the [latest release](https://github.com/MinnDevelopment/strumbot/releases/latest)
1. Unzip and open the resulting directory in a terminal of your choice
1. Change the configuration in `config.json`
1. Run the script for your current platform:
    1. Windows: `run.bat`
    1. Linux: `run.sh`
