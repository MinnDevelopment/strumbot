# How to create a twtich app

The twitch app provides a `client_id` which is required to access stream information.

1. Open the developer dashboard [here][1]
1. Click **Register Your Application**
1. Insert a unique name for your application
1. Set your OAuth redirect URL to `http://localhost` (this is irrelevant)
1. Set the category to **Chat Bot** (also rather irrelevant for our use)
1. Click **Create** and **Manage** on the created application
1. Copy the **Client ID** and insert it into your `config.json` under `twitch.client_id`
1. You are done!


[1]: https://dev.twitch.tv/console/apps
