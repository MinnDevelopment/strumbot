# How to create a bot

To use strumbot you need a discord bot and the authorization token. A bot can be created from the [Developer Dashboard][1].

1. Create an Application
1. Open the **BOT** tab
1. Click **Add Bot**
1. You should see a token section, click **Copy**
1. Insert the token in your `config.json` for `discord.token`.
1. Open the **OAuth2** tab
1. Click the `bot` checkbox in the scopes
1. Check the permissions `Send Messages`, `View Channels`, and `Manage Roles`
1. Copy the link and open it in a new tab
1, Select the server of your choice and authorize the application
1. You are done! The bot should now be ready to use.

[1]: https://discordapp.com/developers/applications
