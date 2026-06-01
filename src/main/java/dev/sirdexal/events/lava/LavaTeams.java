package dev.sirdexal.events.lava;

import dev.sirdexal.events.EventsLog;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages vanilla scoreboard teams for Lava Rising (red/blue/green/admin).
 */
public class LavaTeams {

    private final Map<String, Integer> alivePerTeam = new HashMap<>();

    public void ensureTeams(MinecraftServer server) {
        ServerScoreboard sb = server.getScoreboard();
        ensureTeam(sb, "red", Formatting.RED);
        ensureTeam(sb, "blue", Formatting.BLUE);
        ensureTeam(sb, "green", Formatting.GREEN);
        ensureTeam(sb, "admin", Formatting.GRAY);
    }

    private void ensureTeam(ServerScoreboard sb, String name, Formatting color) {
        Team team = sb.getTeam(name);
        if (team == null) {
            team = sb.addTeam(name);
        }
        team.setColor(color);
    }

    /**
     * Validate that all non-admin players are assigned to a valid team.
     */
    public boolean validateTeams(MinecraftServer server, int teamCount) {
        boolean hasRed = false, hasBlue = false, hasGreen = false;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            String tn = getPlayerTeamName(p);
            if (tn == null) return false; // unassigned player
            if ("admin".equals(tn)) continue;

            switch (tn) {
                case "red" -> hasRed = true;
                case "blue" -> hasBlue = true;
                case "green" -> { if (teamCount >= 3) hasGreen = true; else return false; }
                default -> { return false; } // unknown team
            }
        }
        if (!hasRed || !hasBlue) return false;
        return teamCount < 3 || hasGreen;
    }

    /**
     * Count surviving players per team. Call at start and on each death.
     */
    public void countPlayers(MinecraftServer server) {
        alivePerTeam.clear();
        alivePerTeam.put("red", 0);
        alivePerTeam.put("blue", 0);
        alivePerTeam.put("green", 0);

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.isSpectator()) continue;
            String team = getPlayerTeamName(p);
            if (team != null && alivePerTeam.containsKey(team)) {
                alivePerTeam.merge(team, 1, Integer::sum);
            }
        }
        EventsLog.debug("TEAMS alive: red={} blue={} green={}",
                alivePerTeam.getOrDefault("red", 0),
                alivePerTeam.getOrDefault("blue", 0),
                alivePerTeam.getOrDefault("green", 0));
    }

    public void onPlayerDeath(ServerPlayerEntity player) {
        String team = getPlayerTeamName(player);
        if (team != null && alivePerTeam.containsKey(team)) {
            alivePerTeam.merge(team, -1, Integer::sum);
            if (alivePerTeam.get(team) < 0) alivePerTeam.put(team, 0);
        }
    }

    /**
     * @return the name of the winning team, or null if no winner yet.
     */
    public String checkTeamWin(int teamCount) {
        int teamsAlive = 0;
        String lastAliveTeam = null;
        for (Map.Entry<String, Integer> e : alivePerTeam.entrySet()) {
            if (teamCount < 3 && "green".equals(e.getKey())) continue;
            if (e.getValue() > 0) {
                teamsAlive++;
                lastAliveTeam = e.getKey();
            }
        }
        return (teamsAlive <= 1) ? lastAliveTeam : null;
    }

    public boolean isOnTeam(ServerPlayerEntity player, String teamName) {
        return teamName.equals(getPlayerTeamName(player));
    }

    private String getPlayerTeamName(ServerPlayerEntity player) {
        AbstractTeam team = player.getScoreboardTeam();
        return team != null ? team.getName() : null;
    }

    public boolean joinTeam(MinecraftServer server, ServerPlayerEntity player, String teamName) {
        ServerScoreboard sb = server.getScoreboard();
        Team team = sb.getTeam(teamName);
        if (team == null) return false;
        sb.addScoreHolderToTeam(player.getNameForScoreboard(), team);
        return true;
    }

    public void leaveTeam(MinecraftServer server, ServerPlayerEntity player) {
        ServerScoreboard sb = server.getScoreboard();
        Team team = sb.getScoreHolderTeam(player.getNameForScoreboard());
        if (team != null) {
            sb.removeScoreHolderFromTeam(player.getNameForScoreboard(), team);
        }
    }

    public void autoAssign(MinecraftServer server, int teamCount) {
        java.util.List<ServerPlayerEntity> unassigned = new java.util.ArrayList<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if ("admin".equals(getPlayerTeamName(p))) continue;
            if (getPlayerTeamName(p) == null) unassigned.add(p);
        }
        java.util.Collections.shuffle(unassigned);
        int idx = 0;
        String[] teams = teamCount >= 3 ? new String[]{"red", "blue", "green"} : new String[]{"red", "blue"};
        for (ServerPlayerEntity p : unassigned) {
            joinTeam(server, p, teams[idx % teams.length]);
            idx++;
        }
    }
}
