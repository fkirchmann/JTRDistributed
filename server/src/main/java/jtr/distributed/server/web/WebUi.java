/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.server.web;

import jtr.distributed.server.assignment.Assignment;
import jtr.distributed.server.assignment.AssignmentManager;
import jtr.distributed.server.ServerMain;
import lombok.AllArgsConstructor;
import lombok.val;
import org.glassfish.jersey.server.mvc.Template;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/ui")
@AllArgsConstructor
public class WebUi {
    private final ServerMain serverMain;

    @Path("/status")
    @GET
    @Template(name = "/status.ftl")
    public Map<String, Object> showStatus() {
        Map<String, Object> args = new HashMap<>();
        val currentState = serverMain.getState();
        AssignmentManager assignmentManager = currentState.getFirst();
        Map<String, ServerMain.ClientInfo> clients = currentState.getSecond();

        args.put("assignments", assignmentManager);
        args.put("clients", clients.values()
                .stream().sorted(Comparator.comparing(ServerMain.ClientInfo::getClientId))
                .collect(Collectors.toList()));
        args.put("targetDurationSecs", ServerMain.WORK_PACKAGE_TARGET_DURATION.getSeconds());

        long numberCompleted = assignmentManager.getCompletedAssignments().stream()
                .mapToLong(Assignment::size)
                .sum();
        args.put("progressPercent", ((double) numberCompleted / serverMain.getWordlistGenerator().getSize()) * 100.0);

        Map<String, Long> averageHashrates = serverMain.getClientGuessesPerSecondAverages().getAverages();
        args.put("averageHashrates", averageHashrates);

        long hashrate = 0;
        for(String clientId : clients.keySet()) {
            Long clientHashrate = averageHashrates.get(clientId);
            if(clientHashrate == null) { hashrate += clients.get(clientId).getLastGuessesPerSecond(); }
            if(clientHashrate != null) {
                hashrate += clientHashrate;
            }
        }
        args.put("hashrate", hashrate);
        if(hashrate > 0) {
            long secondsRemaining = (serverMain.getWordlistGenerator().getSize() - numberCompleted) / hashrate;
            args.put("timeRemaining",
                    String.format("%dh %02dm", secondsRemaining / 3600, (secondsRemaining % 3600) / 60));
        }

        args.put("passwordFound", serverMain.getPasswordFound());

        return args;
    }
}
