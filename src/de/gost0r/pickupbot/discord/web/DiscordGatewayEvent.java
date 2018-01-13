package de.gost0r.pickupbot.discord.web;

public enum DiscordGatewayEvent {
	HELLO,
	READY,
	RESUMED,
	INVALID_SESSION,
	
	CHANNEL_CREATE,
	CHANNEL_UPDATE,
	CHANNEL_DELETE,
	CHANNEL_PINS_UPDATE,
	
	GUILD_CREATE,
	GUILD_UPDATE,
	GUILD_DELETE,
	GUILD_BAN_ADD,
	GUILD_BAN_REMOVE,
	GUI�D_EMOJIS_UPDATE,
	GUILD_INTEGRATION_UPDATE,
	GUILD_MEMBER_ADD,
	GUILD_MEMBER_REMOVE,
	GUILD_MEMEBER_UPDATE,
	GUILD_MEMBER_CHUNK,
	GUILD_ROLE_CREATE,
	GUILD_ROLE_UPDATE,
	GUILD_ROLE_DELETE,
	
	MESSAGE_CREATE,
	MESSAGE_UPDATE,
	MESSAGE_DELETE,
	MESSAGE_DELETE_BULK,
	MESSAGE_REACTION_ADD,
	MESSAGE_REACTION_REMOVE,
	MESSAGE_REACTION_REMOVE_ALL,
	
	PRESENCE_UPDATE,
	ACTIVITY_OBJECT,
	TYPING_START,
	USER_UPDATE,
	
	VOICE_STATE_UPDATE,
	VOICE_SERVER_UPDATE,
	
	WEBHOOKS_UPDATE
}
