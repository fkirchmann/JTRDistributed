/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.server.web;

import jtr.distributed.core.events.*;
import jtr.distributed.server.assignment.ActiveAssignment;
import jtr.distributed.server.ServerMain;
import lombok.AllArgsConstructor;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Map;

@Path("/api")
@AllArgsConstructor
public class WebApi {
    private final ServerMain serverMain;

    @GET
    @Path("/test")
    @Produces(MediaType.APPLICATION_JSON)
    public String test() {
        return "\"test\"";
    }

    @POST
    @Path("/event/exit/found")
    @Consumes(MediaType.APPLICATION_JSON)
    public void exitFound(@HeaderParam("clientId") String clientId, PasswordFoundEvent event) {
        serverMain.updateFound(clientId, event);
    }

    @POST
    @Path("/event/exit/notFound")
    @Consumes(MediaType.APPLICATION_JSON)
    public void exitNotFound(@HeaderParam("clientId") String clientId, PasswordNotFoundEvent event) {
        serverMain.updateNotFound(clientId, event);
    }

    @POST
    @Path("/event/exit/error")
    @Consumes(MediaType.APPLICATION_JSON)
    public void exitError(@HeaderParam("clientId") String clientId, ErrorEvent event) {
        serverMain.updateError(clientId, event);
    }

    @POST
    @Path("/event/updateStatus")
    @Consumes(MediaType.APPLICATION_JSON)
    public void updateStatus(@HeaderParam("clientId") String clientId, StatusEvent event) {
        serverMain.updateStatus(clientId, event);
    }

    @POST
    @Path("/getTask")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAssignment(@HeaderParam("clientId") String clientId) {
        ActiveAssignment activeAssignment = serverMain.getOrCreateWorkAssignment(clientId);
        if(activeAssignment == null) {
            return Response.serverError().build();
        }
        return Response.ok(
                new WordlistAssignment(activeAssignment.getBeginIndex(), activeAssignment.getEndIndex()),
                MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/estimateGpuHashrate")
    @Produces(MediaType.TEXT_PLAIN)
    public long estimateGpuHashrate(@QueryParam("clientId") String clientId, @QueryParam("hostId") Integer hostId, @QueryParam("gpuName") String gpuName,
                                    @QueryParam("tflops") Long tflops) {
        return serverMain.getVastStatistician().estimateGpuHashrate(clientId, hostId, gpuName, tflops);
    }

    @GET
    @Path("/getClientHashrate")
    @Produces(MediaType.TEXT_PLAIN)
    public long getClientHashrate(@QueryParam("clientId") String clientId) {
        return serverMain.getClientGuessesPerSecondAverages().getAverages().entrySet().stream()
                .filter(entry -> entry.getKey().contains(clientId))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    @GET
    @Path("/isPasswordFound")
    @Produces(MediaType.TEXT_PLAIN)
    public String getPasswordFound() {
        return Boolean.toString(serverMain.getPasswordFound() != null);
    }
}
