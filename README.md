[live-event]: https://raw.githubusercontent.com/MinnDevelopment/strumbot/master/assets/readme/live-event.png
[update-event]: https://raw.githubusercontent.com/MinnDevelopment/strumbot/master/assets/readme/update-event.png
[vod-event]: https://raw.githubusercontent.com/MinnDevelopment/strumbot/master/assets/readme/vod-event.png
[rank-joining]: https://raw.githubusercontent.com/MinnDevelopment/strumbot/patch-slash-commands/assets/readme/rank-joining.gif
[example-config]: https://github.com/MinnDevelopment/strumbot/blob/master/example-config.json

[ ![docker-pulls](https://img.shields.io/docker/pulls/minnced/strumbot) ](https://hub.docker.com/r/minnced/strumbot)
[ ![release](https://img.shields.io/github/v/tag/minndevelopment/strumbot) ](https://github.com/MinnDevelopment/strumbot/releases/latest)

# Strumbot

A Twitch Stream Notification Bot. This will send notifications to a webhook in your Discord server when the subscribed streamer goes live or changes their game.

## Requirements

- JDK 11 or better
- Stable Internet

## Configurations

The configuration file must be called `config.json` and has to be in the working directory. An example configuration can be found in [`example-config.json`][example-config].
Anything marked with **(optional)** can be set to `null` to be disabled.

The `timezone` property configures the zone which uis used to display the **started at** section of the announcement embed.
This can parse offsets with the formats described [here](https://docs.oracle.com/javase/8/docs/api/java/time/ZoneId.html#of-java.lang.String-) and [here](https://docs.oracle.com/javase/8/docs/api/java/time/ZoneId.html#SHORT_IDS).
If this field is omitted, the timezone will be GMT.

### Discord

This section of the configuration contains settings for the discord side of the bot such as role names and webhook URLs.
Note that the bot uses global role cache, independent of servers, and it is recommended to only have the bot account in one server.

If you don't know how to create a discord bot and get access to the token: [How to make a discord bot](https://github.com/MinnDevelopment/strumbot/blob/master/guides/HOW_TO_CREATE_A_BOT.md)

- `token` The discord bot token
- `stream_notifications` The webhook URL to send stream updates to (see [How to create a webhook](https://github.com/MinnDevelopment/strumbot/blob/master/guides/HOW_TO_CREATE_A_WEBHOOK.md))
- `role_name` Configuration of `type`->`role` to change the default names of the update roles
- `enabled_events` Array of events to publish to the `stream_notifications` webhook
- `logging` Optional webhook URL for errors and warnings printed at runtime (omit or null to disable)

The roles used for updates can be managed by the bot with the `/rank role: <type>` command.
This command will automatically assign the role to the user.

For example, with the configuration `"live": "Stream is Live"` the bot will accept the command `/rank role: live` and assign/remove the role `Stream is Live` for the user.
These commands are *ephemeral*, which means they only show up to the user who invokes them. This way you can use them anywhere without having any clutter in chat!

![rank-joining.gif][rank-joining]


#### Events

![vod-event.png][vod-event]

- [`live`][live-event] When the streamer goes live
- [`update`][update-event] When the streamer changes the current game
- [`vod`][vod-event] When the streamer goes offline (includes vod timestamps for game changes)

### Twitch

This configuration section contains required information to track the stream status.

If you don't know how to make a twitch application and access the client_id: [How to make a twitch app](https://github.com/MinnDevelopment/strumbot/blob/master/guides/HOW_TO_CREATE_A_TWITCH_APP.md)

- `top_clips` The maximum number of top clips to show in the vod event (0 <= x <= 5)
- `client_id` The twitch application's client_id
- `client_secret` The twitch application's client_secret
- `user_login` The username of the tracked streamer

### Example

```json
{
  "discord": {
    "token": "NjUzMjM1MjY5MzM1NjQ2MjA4.*******.*******",
    "stream_notifications": "https://discord.com/api/webhooks/*******/******",
    "logging": null,
    "role_name": {
      "live": "live",
      "vod": "vod",
      "update": "update"
    },
    "enabled_events": ["live", "update", "vod"]
  },
  "twitch": {
    "top_clips": 5,
    "client_id": "*******",
    "client_secret": "*******",
    "user_login": ["Elajjaz", "Distortion2"]
  }
}
```

## Setup

Currently, I only provide 2 setups. Either [docker](https://hub.docker.com) or through a script.
I'm open for pull requests that introduce more or better setups.

### Docker

The image is hosted at [docker hub](https://hub.docker.com/r/minnced/strumbot).

1. Open a terminal in the directory of your choice (which includes the `config.json`!)
1. Pull the image with `docker pull minnced/strumbot:%VERSION%` (Replace `%VERSION%` with the version here: [latest release](https://github.com/MinnDevelopment/strumbot/releases/latest))
1. Change the configuration in `config.json`
1. Create and start a container with this command:
    ```sh
    docker run -d \
      -v ./config.json:/etc/strumbot/config.json \
      --name strumbot \
      --restart unless-stopped \
      minnced/strumbot:%VERSION%
   ```

### Script

1. Download the zip archive from the [latest release](https://github.com/MinnDevelopment/strumbot/releases/latest)
1. Unzip and open the resulting directory in a terminal of your choice
1. Change the configuration in `config.json`
1. Run the script for your current platform:
    1. Windows: `run.bat`
    1. Linux: `run.sh`
