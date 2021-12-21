package de.gost0r.pickupbot.pickup;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.sentry.Sentry;
import org.json.JSONObject;

import de.gost0r.pickupbot.discord.DiscordButton;
import de.gost0r.pickupbot.discord.DiscordButtonStyle;
import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordComponent;
import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.discord.DiscordMessage;
import de.gost0r.pickupbot.pickup.MatchStats.Status;
import de.gost0r.pickupbot.pickup.server.Server;
import de.gost0r.pickupbot.pickup.server.ServerMonitor.ServerState;

public class Match implements Runnable {
	private final static Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private Gametype gametype;
	private MatchState state;
	private int id;

	private Map<String, List<Player>> teamList;
	private Map<GameMap, Integer> mapVotes;
	private Map<Player, MatchStats> playerStats;
	private List<Player> sortedPlayers;
	
	public List<DiscordChannel> threadChannels;
	public List<DiscordMessage> liveScoreMsgs;

	private Server server;
	private Server gtvServer;
	private GameMap map;
	private int[] elo = new int[2];	
	private int[] score = new int[2];
	
	private Player[] captains = new Player[2];
	private int captainTurn;

	private int[] surrender;

	private long startTime;
	private long timeLastPick;
	private boolean pickReminderSent;
	private List<DiscordMessage> pickMessages = new ArrayList<DiscordMessage>();

	private PickupLogic logic;

	private Match() {
		playerStats = new HashMap<Player, MatchStats>();
		teamList = new HashMap<String, List<Player>>();
		teamList.put("red", new ArrayList<Player>());
		teamList.put("blue", new ArrayList<Player>());
		mapVotes = new HashMap<GameMap, Integer>();
		sortedPlayers = new ArrayList<Player>();
		captainTurn = 1;
		threadChannels = new ArrayList<DiscordChannel>();
		liveScoreMsgs = new ArrayList<DiscordMessage>();
	}

	public Match(PickupLogic logic, Gametype gametype, List<GameMap> maplist) {
		this();
		this.logic = logic;
		this.gametype = gametype;

		for (GameMap m : maplist) {
			mapVotes.put(m, 0);
		}
		state = MatchState.Signup;

		surrender = new int[] { gametype.getTeamSize() - 1, gametype.getTeamSize() - 1};
	}

	public Match(int id, long startTime, GameMap map, int[] score, int[] elo,
			Map<String, List<Player>> teamList, MatchState state, Gametype gametype, Server server,
			Map<Player, MatchStats> playerStats) {
		this();
		this.id = id;
		this.startTime = startTime;
		this.map = map;
		this.score = score;
		this.elo = elo;
		this.teamList = teamList;
		this.state = state;
		this.gametype = gametype;
		this.server = server;
		this.playerStats = playerStats;
		
		if (!isOver()) {
			server.startMonitoring(this);
		}

		surrender = new int[] { gametype.getTeamSize() - 1, gametype.getTeamSize() - 1};
	}

	public void reset() {
		if (state == MatchState.Signup) {
			resetSignup();
		} else if (state == MatchState.AwaitingServer) {
			resetAwaitingServer();
		} else if (state == MatchState.Live) {
			resetLive();
		}
	}

	private void resetSignup() {
		// reset mapvote
		for(Player p : playerStats.keySet()) {
			p.resetVotes();
		}
		mapVotes.replaceAll((m, v) -> 0);

		if(!threadChannels.isEmpty()){
			for (DiscordChannel threadChannel : threadChannels){
				threadChannel.delete();
			}
			threadChannels = new ArrayList<DiscordChannel>();
		}
		
		playerStats.clear();
	}

	private void resetAwaitingServer() {
		logic.cancelRequestServer(this);
		resetSignup();
		state = MatchState.Signup;
	}

	private void resetLive() {
		abort();
	}

	public void addPlayer(Player player) {
		if (state == MatchState.Signup && !isInMatch(player)) {
			playerStats.put(player, new MatchStats());
			checkReadyState(player);
		}
	}

	public void removePlayer(Player player, boolean shouldSpam) {
		if ((state == MatchState.Signup || state == MatchState.AwaitingServer) && isInMatch(player)) {
			GameMap map = player.getVotedMap(gametype);
			if (map != null) {
				mapVotes.put(map, mapVotes.get(map) - 1);
				player.voteMap(gametype, null);
			}
			playerStats.remove(player);
			checkServerState();
			logic.cmdStatus(this, player, shouldSpam);
		}
	}

	public void voteMap(Player player, GameMap map) {
		if ((state == MatchState.Signup || state == MatchState.AwaitingServer) && (isInMatch(player) || sortedPlayers.contains(player))) {
			GameMap oldMap = player.getVotedMap(gametype);
			if (oldMap != null) {
				mapVotes.put(oldMap, mapVotes.get(oldMap) - 1);
				player.voteMap(gametype, null);
			}
			mapVotes.put(map, mapVotes.get(map) + 1);
			player.voteMap(gametype, map);
			String msg = Config.pkup_map;
			msg = msg.replace(".map.", map.name);
			logic.bot.sendNotice(player.getDiscordUser(), msg);
		} else {
			logic.bot.sendNotice(player.getDiscordUser(), Config.map_cannot_vote);
		}
	}

	public void voteSurrender(Player player) {
		long timeUntilSurrender = (startTime + 300000L) - System.currentTimeMillis(); // 5min in milliseconds
		if (timeUntilSurrender < 0) {
			if (!player.hasVotedSurrender()) {
				player.voteSurrender();
				int teamnum = getTeam(player).equals("red") ? 0 : 1;
				surrender[teamnum]--;
				checkSurrender();
				if (!isOver()) {
					String msg = Config.pkup_surrender_cast;
					msg = msg.replace(".num.", String.valueOf(surrender[teamnum]));
					msg = msg.replace(".s.", surrender[teamnum] > 1 ? "s" : "");
					logic.bot.sendNotice(player.getDiscordUser(), msg);
				}
			} else {
				logic.bot.sendNotice(player.getDiscordUser(), Config.player_already_surrender);
			}
		} else {
			timeUntilSurrender /= 1000d;
			String min = String.valueOf((int) (Math.floor(timeUntilSurrender / 60d)));
			String sec = String.valueOf((int) (Math.floor(timeUntilSurrender % 60d)));
			min = min.length() == 1 ? "0" + min : min;
			sec = sec.length() == 1 ? "0" + sec : sec;
			String msg = Config.pkup_surrender_time;
			msg = msg.replace(".time.", min + ":" + sec);
			logic.bot.sendNotice(player.getDiscordUser(), msg);
		}
	}

	public void checkSurrender() {
		for (int i = 0; i < 2; ++i) {
			if (surrender[i] <= 0) {
				state = MatchState.Surrender;
				try {
					server.getServerMonitor().surrender(i);
				} catch (Exception e) {
					LOGGER.log(Level.WARNING, "Exception: ", e);
					Sentry.capture(e);
				}
				cleanUp();
				sendAftermath();
				logic.matchRemove(this);
				logic.db.saveMatch(this);
			}
		}
	}

	public void checkReadyState(Player player) {
		if (playerStats.keySet().size() == gametype.getTeamSize() * 2) {
			state = MatchState.AwaitingServer;
			logic.cmdStatus(this, player, true);
			
			// Compute region majority
			logic.requestServer(this);
		} else {
			logic.cmdStatus(this, player, true);
		}
	}

	public void checkServerState() {
		if (state == MatchState.AwaitingServer && playerStats.keySet().size() != gametype.getTeamSize() * 2) {
			state = MatchState.Signup;
			logic.cancelRequestServer(this);
		}
	}

	public void abort() {
		state = MatchState.Abort;
		cleanUp();
		logic.db.saveMatch(this);

		for (DiscordChannel threadChannel : threadChannels){
			threadChannel.archive();
		}

		if (gtvServer != null) {
			gtvServer.free();
			gtvServer.sendRcon("gtv_disconnect 1");
		}
	}

	public void abandon(Status status, List<Player> involvedPlayers) {
		state = MatchState.Abandon;
		cleanUp();
		sendAftermath(status, involvedPlayers);
		logic.matchRemove(this);
		logic.db.saveMatch(this);

		for (DiscordChannel threadChannel : threadChannels){
			threadChannel.archive();
		}
		
		if (gtvServer != null) {
			gtvServer.free();
			gtvServer.sendRcon("gtv_disconnect 1");
		}
	}

	public void end() {
		state = MatchState.Done;
		cleanUp();
		sendAftermath();
		logic.matchRemove(this);
		logic.db.saveMatch(this);
		logic.bot.sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), null, getMatchEmbed());

		for (DiscordChannel threadChannel : threadChannels){
			threadChannel.archive();
		}
		
		if (gtvServer != null) {
			gtvServer.free();
			gtvServer.sendRcon("gtv_disconnect 1");
		}
	}

	public void cancelStart() {
		state = MatchState.Abort;
		cleanUp();
		logic.matchStarted(this);
		logic.matchRemove(this);
	}

	private void cleanUp() {
		for(Player p : playerStats.keySet()) {
			p.resetVotes();
		}
		server.free();

		logic.matchEnded();
	}

	private void sendAftermath() {
		StringBuilder fullmsg = new StringBuilder(Config.pkup_aftermath_head);
		fullmsg = new StringBuilder(fullmsg.toString().replace(".gamenumber.", String.valueOf(id)).replace(".gametype.", gametype.getName()).replace(".map.", map.name));
		for (int i = 0; i < 2; ++i) {
			int opp = (i + 1) % 2;
			String teamname = (i == 0) ? "Red" : "Blue";
			StringBuilder msg = new StringBuilder(Config.pkup_aftermath_result);
			msg = new StringBuilder(msg.toString().replace(".team.", teamname));
			if (score[i] > score[opp]) {
				msg = new StringBuilder(msg.toString().replace(".result.", "won"));
			} else if (score[i] < score[opp]) {
				msg = new StringBuilder(msg.toString().replace(".result.", "lost"));
			} else {
				msg = new StringBuilder(msg.toString().replace(".result.", "draw"));
			}
			msg = new StringBuilder(msg.toString().replace(".score.", score[i] + "-" + score[opp]));
			
			for (Player p : teamList.get(teamname.toLowerCase())) {
				String msgelo = Config.pkup_aftermath_player;
				msgelo = msgelo.replace(".player.", p.getDiscordUser().getMentionString());
				String elochange = ((p.getEloChange() >= 0) ? "+" : "") + p.getEloChange();
				msgelo = msgelo.replace(".elochange.", elochange);
				msg.append(" ").append(msgelo);
			}
			fullmsg.append("\n").append(msg);
		}
		for (Player player : getPlayerList()) {
			if (player.didChangeRank()) {
				String msg = Config.pkup_aftermath_rank;
				msg = msg.replace(".player.", player.getDiscordUser().getMentionString());
				msg = msg.replace(".updown.", player.getEloChange() > 0 ? "up" : "down");
				msg = msg.replace(".rank.", player.getRank().getEmoji());
				fullmsg.append("\n").append(msg);
			}
		}
		
		logic.bot.sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), fullmsg.toString());
	}

	private void sendAftermath(Status status, List<Player> involvedPlayers) {
		String fullmsg = Config.pkup_aftermath_head;
		fullmsg = fullmsg.replace(".gametype.", gametype.getName());
		fullmsg = fullmsg.replace(".gamenumber.", String.valueOf(id));
		fullmsg = fullmsg.replace(".map.", map.name);

		String msg = Config.pkup_aftermath_abandon_1;
		msg = msg.replace(".reason.", status.name());
		fullmsg += "\n" + msg;

		if (!involvedPlayers.isEmpty()) { // only if we have people who left	
			StringBuilder playerlist = new StringBuilder();
			for (Player player : involvedPlayers) {
				if (playerlist.length() > 0) {
					playerlist.append(" ");
				}
				playerlist.append(player.getUrtauth());
			}
			String be = involvedPlayers.size() == 1 ? "was" : "were";

			msg = Config.pkup_aftermath_abandon_2;
			msg = msg.replace(".players.", playerlist.toString());
			msg = msg.replace(".be.", be);
			fullmsg += "\n" + msg;
		}

		logic.bot.sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), fullmsg);
	}

	public void launch(Server server) {

		if (!isOver() && state != MatchState.Live) {
			
			this.server = server;
			server.take();

			for (Player player : getPlayerList()) {				
				for (Match m : logic.playerInMatch(player)) {
					if (m == this) continue;
					m.removePlayer(player, false);
				}
			}

			String threadTitle = Config.pkup_go_pub_threadtitle;
			threadTitle = threadTitle.replace(".ID.", String.valueOf(logic.db.getLastMatchID() + 1));

			for (DiscordChannel publicChannel : logic.getChannelByType(PickupChannelType.PUBLIC)){
				threadChannels.add(logic.bot.createThread(publicChannel, threadTitle));
			}

			logic.matchStarted(this);
			timeLastPick = System.currentTimeMillis();
			sortPlayers();

		}
	}

	public void sortPlayers() {
		// Sort players by elo
		List<Player> playerList = new ArrayList<Player>(playerStats.keySet());

		sortedPlayers.add(playerList.get(0));
		for (Player player : playerList) {
			for (Player sortedPlayer : sortedPlayers) {
				if (player.getElo() >= sortedPlayer.getElo() && !player.equals(sortedPlayer)) {
					sortedPlayers.add(sortedPlayers.indexOf(sortedPlayer), player);
					break;
				}
			}
			if (!sortedPlayers.contains(player)) {
				sortedPlayers.add(player);
			}
		}
		
		captains[0] = sortedPlayers.get(0);
		teamList.get("red").add(captains[0]);
		
		captains[1] = sortedPlayers.get(1);
		teamList.get("blue").add(captains[1]);
		
		sortedPlayers.remove(0);
		sortedPlayers.remove(0);

		if (!sortedPlayers.isEmpty()){
			String captainAnnouncement = Config.pkup_go_pub_captains;
			captainAnnouncement = captainAnnouncement.replace(".captain1.", captains[0].getDiscordUser().getMentionString());
			captainAnnouncement = captainAnnouncement.replace(".captain2.", captains[1].getDiscordUser().getMentionString());
			logic.bot.sendMsg(threadChannels, captainAnnouncement);

			String captainDm = Config.pkup_go_captains;
			logic.bot.sendMsg(captains[0].getDiscordUser(), captainDm);
			logic.bot.sendMsg(captains[1].getDiscordUser(), captainDm);
		}
		
		checkTeams();
		/*
		
		if (true) { // WIP
			// 5: i=0,1 => red: (0, 9) (2, 7) -> blue: (1, 8) (3, 6)   if cond: (4, 5)
			// 4: i=0,1 => red: (0, 7) (2, 5) -> blue: (1, 7) (3, 4)   if cond: none
			// 3: i=0   => red: (0, 5)        -> blue: (1, 4)          if cond: (2, 3)
			// 2: i=0   => red: (0, 3)        -> blue: (1, 2)          if cond: none
			// 1: i=-   => red: none          -> blue: none            if cond: (0, 1)
			for (int i = 0; i < Math.floor(gametype.getTeamSize() / 2); ++i) {
				teamList.get("red").add(sortPlayers.get(i*2));
				teamList.get("red").add(sortPlayers.get(((gametype.getTeamSize()*2)-1)-i*2));

				teamList.get("blue").add(sortPlayers.get(i*2+1));
				teamList.get("blue").add(sortPlayers.get(((gametype.getTeamSize()*2)-1)-i*2-1));
			}

			if ((gametype.getTeamSize() % 2) == 1) {
				int better = gametype.getTeamSize() - 1;
				int worse = gametype.getTeamSize();

				// compute avg elo up till now
				elo = new int[] {0, 0};
				for (Player p : teamList.get("red")) elo[0] += p.getElo();
				for (Player p : teamList.get("blue")) elo[1] += p.getElo();
				elo[0] /= Math.max(1, gametype.getTeamSize() - 1);
				elo[1] /= Math.max(1, gametype.getTeamSize() - 1);

				if (elo[0] > elo[1]) {
					teamList.get("red").add(sortPlayers.get(worse));
					teamList.get("blue").add(sortPlayers.get(better));
				} else {
					teamList.get("red").add(sortPlayers.get(better));
					teamList.get("blue").add(sortPlayers.get(worse));
				}
			}
			
			logic.matchStarted(this);
			
			// do important changes that affect possibly other matches/servers/playerlists outside the thread!
			
			new Thread(this).start();
			
		}
		*/
	}
	
	public void checkTeams() {
		if (sortedPlayers.isEmpty() && state == MatchState.AwaitingServer) {
			state = MatchState.Live;
			new Thread(this).start(); // do important changes that affect possibly other matches/servers/playerlists outside the thread!
		}
		else {
			List<DiscordComponent> buttons = new ArrayList<DiscordComponent>();
			int choiceNumber = 3;
			if (sortedPlayers.size() < choiceNumber) {
				choiceNumber = sortedPlayers.size();
			}
			for (int i = 0; i < choiceNumber; i++) {
				DiscordButton button = new DiscordButton(DiscordButtonStyle.BLURPLE);
				button.custom_id = Config.INT_PICK + "_" + i;
				button.label = sortedPlayers.get(i).getUrtauth() + " (" + sortedPlayers.get(i).getElo() + ")";
				button.emoji = sortedPlayers.get(i).getRank().getEmojiJSON();
				buttons.add(button);
			}
			
			// Include in the choices players that played less than 10 games to allow for new player skill uncertainty
			if (choiceNumber < sortedPlayers.size()) {
				for (int i = choiceNumber; i < sortedPlayers.size(); i++) {
					int matchPlayed = logic.db.getNumberOfGames(sortedPlayers.get(i));
					if (matchPlayed < 30) {
						DiscordButton button = new DiscordButton(DiscordButtonStyle.GREY);
						button.custom_id = Config.INT_PICK + "_" + i;
						button.label = sortedPlayers.get(i).getUrtauth();
						button.emoji = new JSONObject().put("name", "\u2753");
						buttons.add(button);
					}
				}
			}
			
			String pickPromptMsg = Config.pkup_go_pub_pick;
			pickPromptMsg = pickPromptMsg.replace(".captain.", captains[captainTurn].getDiscordUser().getMentionString());
			pickMessages = logic.bot.sendMsgToEdit(threadChannels, pickPromptMsg, null, buttons);
		}
	}

	public boolean isCaptainTurn(Player player) {
		return captains[captainTurn].getUrtauth().equals(player.getUrtauth());
	}

	public Player getCaptainsTurn() {
		return captains[captainTurn];
	}

	public void pick(Player captain, int pick) {
		String pickMsg = Config.pkup_go_pub_pickjoin;
		pickMsg = pickMsg.replace(".pick.", sortedPlayers.get(pick).getDiscordUser().getMentionString());
		
		if (captain.getUrtauth().equals(captains[0].getUrtauth())) {
			teamList.get("red").add(sortedPlayers.get(pick));
			pickMsg = pickMsg.replace(".color.", "red");
		}
		else {
			teamList.get("blue").add(sortedPlayers.get(pick));
			pickMsg = pickMsg.replace(".color.", "blue");
		}
		
		sortedPlayers.remove(pick);
		logic.bot.sendMsg(threadChannels, pickMsg);
		captainTurn = 1 - captainTurn;
		timeLastPick = System.currentTimeMillis();

		for (DiscordMessage pickMessage : pickMessages){
			pickMessage.delete();
		}
		
		if (sortedPlayers.size() == 1) {
			pick(captains[captainTurn], 0);
			return;
		}
		checkTeams();
	}

	public long getTimeLastPick() {
		return timeLastPick;
	}

	public boolean getPickReminderSent() {
		return pickReminderSent;
	}

	public void setPickReminderSent(boolean reminderSent) {
		pickReminderSent = reminderSent;
	}


	// DONT CALL THIS OUTSIDE OF launch() !!!
	public void run() {	
		startTime = System.currentTimeMillis();
		
		gtvServer = logic.setupGTV();
		
		Random rand = new Random();
		int password = rand.nextInt((999999-100000) + 1) + 100000;
		server.password = String.valueOf(password);
		LOGGER.info("Password: " + server.password);

		// Get most voted map
		List<GameMap> mapList = getMostMapVotes();
		if (mapList.size() == 0) {
			logic.bot.sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), "ERROR: NO MAP FOR GAMETYPE");
			cancelStart();
			return;
		}
		this.map = mapList.size() == 1 ? mapList.get(0) : mapList.get(rand.nextInt(mapList.size()-1));
		LOGGER.info("Map: " + this.map.name);

		// avg elo
		elo = new int[] {0, 0};
		for (Player p : teamList.get("red")) elo[0] += p.getElo();
		for (Player p : teamList.get("blue")) elo[1] += p.getElo();
		elo[0] /= gametype.getTeamSize();
		elo[1] /= gametype.getTeamSize();

		LOGGER.info("Team Red: " + elo[0] + " " + Arrays.toString(teamList.get("red").toArray()));
		LOGGER.info("Team Blue: " + elo[1] + " " + Arrays.toString(teamList.get("blue").toArray()));

		id = logic.db.createMatch(this);

		// MESSAGE HYPE

		String msg = Config.pkup_go_pub_head;
		msg = msg.replace(".gamenumber.", String.valueOf(id));
		msg = msg.replace(".gametype.", gametype.getName());
		msg = msg.replace(".elo.", String.valueOf((elo[0] + elo[1])/2));
		if (server.region == Region.NA) {
			msg = msg.replace(".region.", ":flag_us:");
		} else if (server.region == Region.EU) {
			msg = msg.replace(".region.", ":flag_eu:");
		} else {
			msg = msg.replace(".region.", server.region.name());
		}
		StringBuilder fullmsg = new StringBuilder(msg);

		msg = Config.pkup_map_list;
		msg = msg.replace(".gametype.", gametype.getName());			
		if (getMostMapVotes().size() == mapVotes.keySet().size()) {
			msg = msg.replace(".maplist.", "NO VOTES - RANDOM!");
		}
		else {
			msg = msg.replace(".maplist.", getMapVotes(true));
		}
		fullmsg.append("\n").append(msg);

		String[] teamname = {"Red", "Blue"};
		for (String team : teamname) {
			StringBuilder playernames = new StringBuilder();
			for (Player p : teamList.get(team.toLowerCase())) {
				if (!playernames.toString().equals("")) {
					playernames.append(" ");
				}
				playernames.append(p.getDiscordUser().getMentionString());
			}
			msg = Config.pkup_go_pub_team;
			msg = msg.replace(".team.", team);
			msg = msg.replace(".gametype.", gametype.getName());
			msg = msg.replace(".playerlist.", playernames.toString());
			fullmsg.append("\n").append(msg);
		}

		msg = Config.pkup_go_pub_map;
		msg = msg.replace(".map.", this.map.name);
		msg = msg.replace(".gametype.", gametype.getName());
		fullmsg.append("\n").append(msg);


		msg = Config.pkup_go_pub_calm;
		if (gtvServer == null) {
			msg = Config.pkup_go_pub_calm_notavi;
		}
		//msg = msg.replace(".elo.", String.valueOf((elo[0] + elo[1])/2));
		fullmsg.append("\n").append(msg);

		logic.bot.sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), fullmsg.toString());

		msg = Config.pkup_go_player;
		msg = msg.replace(".server.", server.getAddress());
		msg = msg.replace(".password.", server.password);
		for (String team : teamList.keySet()) {
			for (Player player : teamList.get(team)) {
				String msg_t = msg.replace(".team.", team.toUpperCase());
				logic.bot.sendMsg(player.getDiscordUser(), msg_t);
			}
		}

		msg = Config.pkup_go_pub_sent;
		msg = msg.replace(".gametype.", gametype.getName());
		logic.bot.sendMsg(logic.getChannelByType(PickupChannelType.PUBLIC), msg);

		// set server data
		server.sendRcon("g_password " + server.password);
		for (String s : this.gametype.getConfig()) {
			server.sendRcon(s);
		}
		server.sendRcon("map " + this.map.name);
		server.sendRcon("g_warmup 10");
		
		logic.setLastMapPlayed(gametype, map);
		
		if (gtvServer != null) {
			gtvServer.sendRcon("gtv_connect " + server.getAddress() + "  " + server.password);
		}
		
		server.startMonitoring(this);
		
		liveScoreMsgs = logic.bot.sendMsgToEdit(threadChannels, "", getMatchEmbed(), null);
	}

	List<GameMap> getMostMapVotes() {
		List<GameMap> mapList = new ArrayList<GameMap>();
		int currentVotes = -1;
		for (GameMap map : mapVotes.keySet()) {
			if (currentVotes == -1) {
				mapList.add(map);
				currentVotes = mapVotes.get(map);
				continue;
			}

			if (mapVotes.get(map) > currentVotes) {
				mapList.clear();
				mapList.add(map);
				currentVotes = mapVotes.get(map);
			} else if (mapVotes.get(map) == currentVotes) {
				mapList.add(map);
			}
		}
		return mapList;
	}

	public String getMapVotes(boolean skipNull) {
		
		List<GameMap> mostMapVotes = getMostMapVotes();
		StringBuilder msg = new StringBuilder("None");
		for (GameMap map : mapVotes.keySet()) {
			if (skipNull && mapVotes.get(map) == 0 && !logic.getLastMapPlayed(gametype).name.equals(map.name)) continue;
			if (msg.toString().equals("None")) {
				msg = new StringBuilder();
			} else {
				msg.append(" - ");
			}
			LOGGER.info(logic.getLastMapPlayed(gametype).name + " " + map.name);
			if (logic.getLastMapPlayed(gametype).name.equals(map.name)) {
				String mapString = "~~" + map.name + "~~";
				msg.append(mapString);
			}
			else {
				String mapString = map.name + ": " + mapVotes.get(map);
				if (mostMapVotes.size() < mapVotes.keySet().size() && mostMapVotes.contains(map)) {
					mapString = "**" + mapString + "**";
				}
				msg.append(mapString);
			}
		}
		return msg.toString();
	}

	public boolean isOver() {
		return state == MatchState.Done || state == MatchState.Abort || state == MatchState.Abandon || state == MatchState.Surrender;
	}

	public MatchState getMatchState() {
		return state;
	}

	public Gametype getGametype() {
		return gametype;
	}

	public boolean isInMatch(Player player) {
		return playerStats.containsKey(player);
	}

	public Set<GameMap> getMapList() {
		return mapVotes.keySet();
	}

	public int getID() {
		return id;
	}

	public void setLogic(PickupLogic logic) {
		this.logic = logic;
	}

	public PickupLogic getLogic() {
		return logic;
	}

	public Server getServer() {
		return server;
	}

	public long getStartTime() {
		return startTime;
	}

	public GameMap getMap() {
		return map;
	}

	public int[] getElo() {
		return elo;
	}

	public int getEloRed() {
		return elo[0];
	}

	public int getEloBlue() {
		return elo[1];
	}

	public void setScore(int[] score) {
		this.score = score;
	}

	public List<Player> getTeamRed() { return teamList.get("red"); }

	public List<Player> getTeamBlue() {
		return teamList.get("blue");
	}

	public List<Player> getPlayerList() {
		return new ArrayList<Player>(playerStats.keySet());
	}

	public String getTeam(Player player) {
		for(String team : teamList.keySet()) {
			if (teamList.get(team).contains(player)) {
				return team;
			}
		}
		return null;
	}

	public int getScoreRed() {
		return score[0];
	}

	public int getScoreBlue() {
		return score[1];
	}

	public MatchStats getStats(Player player) {
		return playerStats.get(player);
	}

	public int getPlayerCount() {
		return playerStats.keySet().size();
	}

	public String getIngameInfo() {
		String info;
		if (state == MatchState.AwaitingServer || server.getServerMonitor() == null) {
			info = "Captains's pick";
		} else if (state == MatchState.Live) {
			ServerState serverState = server.getServerMonitor().getState();
			info = serverState.name();
			if (serverState == ServerState.LIVE) {
				info += " - " + server.getServerMonitor().getGameTime();
			}
			info += " (" + server.getServerMonitor().getScore() + ")";
			return info;
		} else {
			info = state.name();
			info += " (" + score[0] + "-" + score[1] + ")";
		}
		return info;
	}

	public String getMatchInfo() {
		
		StringBuilder redplayers = new StringBuilder("None");
		for (Player p : teamList.get("red")) {
			if (redplayers.toString().equals("None")) {
				redplayers = new StringBuilder(p.getUrtauth());
			} else {
				redplayers.append(" ").append(p.getUrtauth());
			}
		}
		StringBuilder blueplayers = new StringBuilder("None");
		for (Player p : teamList.get("blue")) {
			if (blueplayers.toString().equals("None")) {
				blueplayers = new StringBuilder(p.getUrtauth());
			} else {
				blueplayers.append(" ").append(p.getUrtauth());
			}
		}

		String msg = Config.pkup_match_print_info;
		msg = msg.replace(".gamenumber.", id == 0 ? String.valueOf(logic.db.getLastMatchID() + 1) : String.valueOf(id));
		msg = msg.replace(".gametype.", gametype.getName());
		msg = msg.replace(".map.", map != null ? map.name : "ut4_?");
		msg = msg.replace(".redteam.", redplayers.toString());
		msg = msg.replace(".blueteam.", blueplayers.toString());
		msg = msg.replace(".ingame.", getIngameInfo());

		return msg;
	}
	
	public DiscordEmbed getMatchEmbed() {
		ServerState serverState = null;
		if (server.isTaken()) {
			serverState = server.getServerMonitor().getState();
		}
		
		DiscordEmbed embed = new DiscordEmbed();
		
		String region_flag;
		if (server == null || server.region == null) {
			region_flag = "";
		} else if (server.region == Region.NA) {
			region_flag =  ":flag_us:";
		} else if (server.region == Region.EU) {
			region_flag = ":flag_eu:";
		} else {
			region_flag = server.region.name();
		}
		
		if (serverState == ServerState.LIVE && state == MatchState.Live && server != null) {
			embed.title  = region_flag + " Match #" + id + " (" + server.getServerMonitor().getGameTime() + ")";
		}
		else {
			embed.title = region_flag + " Match #" + id ;
		}
		
		embed.color = 7056881;
		embed.description = map != null ? "**" + gametype.getName() + "** - *" + map.name + "*" : "null";
		
		StringBuilder red_team_player_embed = new StringBuilder();
		StringBuilder red_team_score_embed = new StringBuilder();
		StringBuilder blue_team_player_embed = new StringBuilder();
		StringBuilder blue_team_score_embed = new StringBuilder();
		
		// Order teams scores by score
		List<Map.Entry<Player, MatchStats>> entries = new ArrayList<Map.Entry<Player, MatchStats>>(playerStats.entrySet());
		entries.sort((a, b) -> Integer.compare(b.getValue().score[0].score, a.getValue().score[0].score));
		
		for (Map.Entry<Player, MatchStats> entry : entries) {
			String country;
			if( entry.getKey().getCountry().equalsIgnoreCase("NOT_DEFINED")) {
				country =  "<:puma:849287183474884628>";
			}
			else {
				country = ":flag_" + entry.getKey().getCountry().toLowerCase() + ":";
			}
			String player_row = country + " \u200b \u200b " +  entry.getKey().getUrtauth() + "\n";
			String score_row = entry.getValue().score[0].score +  "/" + entry.getValue().score[0].deaths + "/" + entry.getValue().score[0].assists + "\n";
			if (teamList.get("red").contains(entry.getKey())) {
				red_team_player_embed.append(player_row);
				red_team_score_embed.append(score_row);
			}
			else if (teamList.get("blue").contains(entry.getKey())) {
				blue_team_player_embed.append(player_row);
				blue_team_score_embed.append(score_row);
			}
		}
		
		embed.addField("<:rush_red:510982162263179275> \u200b \u200b " + getScoreRed() + "\n \u200b", red_team_player_embed.toString(), true);
		embed.addField("K/D/A" + "\n \u200b", red_team_score_embed.toString(), true);
		embed.addField("\u200b", "\u200b", false);
		embed.addField("<:rush_blue:510067909628788736> \u200b \u200b " + getScoreBlue() + "\n \u200b", blue_team_player_embed.toString(), true);
		embed.addField("K/D/A" + "\n \u200b", blue_team_score_embed.toString(), true);

		Date startDate = new Date(startTime);
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		embed.timestamp = df.format(startDate);
		embed.footer = state.name();
		
		return embed;
	}

	public Region getPreferredServerRegion() {
		Map<Region, Float> playerRegionPercentage= new HashMap<Region, Float>();
		
		// Init all region percentage to 0
		for(Region r : Region.values()) {
			playerRegionPercentage.put(r, 0.0F);
		}
		
		Float percentPlayer = (float) (100/playerStats.keySet().size());
		
		// Update all regions percentages
		for(Player p : playerStats.keySet()) {
			Region PlayerRegion = p.getRegion();
			Float RegionPercent = playerRegionPercentage.get(PlayerRegion);
			playerRegionPercentage.put(p.getRegion(), RegionPercent + percentPlayer);
		}
		
		Float bestRegionPercentage = 0.0F;
		Region RegionMajoritary = Region.NA;
		// Memorize which region has the most player occurrence
		for(Region r : Region.values()) {
			if(playerRegionPercentage.get(r) >= bestRegionPercentage) {
				bestRegionPercentage = playerRegionPercentage.get(r);
				RegionMajoritary = r;
			}
		}
		
		// If region percentage has more than 60% players, it is the majoritary region
		if(bestRegionPercentage >= 70.0)
		{
			return RegionMajoritary;
		}
		// Case when it is NA team vs EU team with more EU players and without "exotic" players
		else if (playerRegionPercentage.get(Region.EU) >= 60.0 && playerRegionPercentage.get(Region.NA) >= 40.0 )
		{
			return Region.EU;
		}
		// If there are 50% EU players and at least one exotic player
		else if (playerRegionPercentage.get(Region.EU) == 50.0 && ( (playerRegionPercentage.get(Region.AF) >= percentPlayer) ||
																	(playerRegionPercentage.get(Region.AN) >= percentPlayer) ||
																	(playerRegionPercentage.get(Region.AS) >= percentPlayer)))
		{
			return Region.EU;
		}
		// If there are 50% EU players and at least one Oceanic or Latino player
		else if (playerRegionPercentage.get(Region.EU) >= 60.0 && ( (playerRegionPercentage.get(Region.SA) >= percentPlayer) ||
																	(playerRegionPercentage.get(Region.OC) >= percentPlayer)))
		{
			return Region.NA;
		}
		else
		{
			return Region.NA;
		}
	}

	@Override
	public String toString() {
		String msg = "";
		switch(state) {
		case Signup: msg = Config.pkup_match_print_signup; break;
		case AwaitingServer: msg = Config.pkup_match_print_server; break;
		case Live: msg = Config.pkup_match_print_live; break;
		case Done: msg = Config.pkup_match_print_done; break;
		case Abort: msg = Config.pkup_match_print_abort; break;
		case Abandon: msg = Config.pkup_match_print_abandon; break;
		case Surrender: msg = Config.pkup_match_print_sur; break;
		default: break;
		}
		
		StringBuilder playernames = new StringBuilder("None");
		for (Player p : playerStats.keySet()) {
			if (playernames.toString().equals("None")) {
				playernames = new StringBuilder(p.getDiscordUser().getMentionString());
			} else {
				playernames.append(" ").append(p.getDiscordUser().getMentionString());
			}
		}
		
		msg = msg.replace(".gamenumber.", String.valueOf(id));
		msg = msg.replace(".gametype.", gametype.getName());
		msg = msg.replace(".map.", map != null ? map.name : "null");
		msg = msg.replace(".elored.", String.valueOf(elo[0]));
		msg = msg.replace(".eloblue.", String.valueOf(elo[1]));
		msg = msg.replace(".playernumber.", String.valueOf(getPlayerCount()));
		msg = msg.replace(".maxplayer.", String.valueOf(gametype.getTeamSize() * 2));
		msg = msg.replace(".playerlist.", playernames.toString());
		msg = msg.replace(".score.", score[0] + " " + score[1]);
		return msg;
	}
	
	public void updateScoreEmbed() {
		if (state == MatchState.Live) {
			score = server.getServerMonitor().getScoreArray();
		}
		for (DiscordMessage liveScoreMsg : liveScoreMsgs) {
			liveScoreMsg.edit(null, getMatchEmbed());
		}
	}
	
	public Server getGtvServer() {
		return gtvServer;
	}
}
