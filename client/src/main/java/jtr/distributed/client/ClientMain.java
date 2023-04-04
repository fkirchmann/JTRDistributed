/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.client;

import com.esotericsoftware.minlog.Log;
import jtr.distributed.client.john.JTRunner;
import jtr.distributed.core.LocalTimeLogger;
import jtr.distributed.core.events.*;
import jtr.distributed.core.wordlist.ExampleWordlistGenerator;
import jtr.distributed.core.wordlist.WordlistGenerator;
import lombok.SneakyThrows;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.jackson.JacksonFeature;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;

public class ClientMain {

    private static final int TIMEOUT = 5 * 1000; // ms
    private static final int ERROR_RESTART_DELAY = 10 * 1000; // ms
    private static final int LOG_LEVEL = Log.LEVEL_DEBUG;

    @SneakyThrows
    public static void main(String[] args) {
        Log.set(LOG_LEVEL);
        Log.setLogger(new LocalTimeLogger());

        if(args.length < 5) {
            Log.error("Usage: <clientId> <apikey> <johnPath> <devices> <hashfile> <john_extra_args ..>");
            System.exit(1);
            return;
        }

        String clientId = args[0];
        String apiKey = args[1];
        File johnPath = new File(args[2]);
        String devices = args[3];
        File hashfilePath = new File(args[4]);
        String[] johnExtraArgs = Arrays.copyOfRange(args, 5, args.length);
        if(!johnPath.isDirectory()) {
            Log.error("John path \"" + johnPath.getAbsolutePath() + "\" is not a directory");
            System.exit(1);
            return;
        }
        if(!hashfilePath.isFile()) {
            Log.error("Hashfile path \"" + hashfilePath.getAbsolutePath() + "\" is not a file");
            System.exit(1);
            return;
        }

        new ClientMain(clientId, apiKey, johnPath, devices, hashfilePath, johnExtraArgs);
    }

    private final ClientConfig webClientConfig = new ClientConfig();
    private final static String LOG_MAIN = "main";

    private final String apiUrl;
    private final String apiKey;
    private final String clientId;

    @SneakyThrows(InterruptedException.class)
    public ClientMain(String clientId, String apiKey, File johnPath, String devices, File hashfilePath,
                      String[] johnExtraArgs) {
        this.clientId = clientId;
        if("local".equals(apiKey)) {
            this.apiUrl = "http://localhost:45678/jtr-distributed/api/";
        } else {
            this.apiUrl = "https://example.com/jtr-distributed/api/";
        }
        this.apiKey = apiKey;

        while(true) {
            JTRunner runner = null;
            try {
                Log.info(LOG_MAIN, "Retrieving assignment...");
                WordlistAssignment assignment = getAssignment();
                Log.info(LOG_MAIN,"Starting JTR...");
                WordlistGenerator wg = new ExampleWordlistGenerator();
                runner = new JTRunner(johnPath, devices, hashfilePath, wg, assignment.getBeginIndex(),
                                                assignment.getEndIndex(), johnExtraArgs);
                Log.info(LOG_MAIN,"Waiting for events...");
                while (true) {
                    JTEvent event = runner.takeNextEvent();
                    Log.trace(LOG_MAIN, "Got Event: " + event.toString());
                    if(event instanceof StatusEvent){
                        updateStatus((StatusEvent) event);
                    } else if(event instanceof ExitEvent) {
                        if(event instanceof PasswordNotFoundEvent) {
                            updatePasswordNotFound((PasswordNotFoundEvent) event);
                        } else if(event instanceof PasswordFoundEvent) {
                            updatePasswordFound((PasswordFoundEvent) event);
                        } else if(event instanceof ErrorEvent) {
                            ErrorEvent errorEvent = (ErrorEvent) event;
                            updateError(errorEvent);
                            throw new IOException("ErrorEvent: " + errorEvent.getMessage(), errorEvent.getException());
                        }
                        Log.trace(LOG_MAIN, "|| EventBus complete ||");
                        break;
                    }
                }
                Log.info(LOG_MAIN,"Assignment done, killing process...");
                runner.kill();
            } catch (IOException | ProcessingException e) {
                if(runner != null) {
                    runner.kill();
                }
                Log.warn("Client failed due to Exception. ", e);
                Log.info("Restarting in " + ClientMain.ERROR_RESTART_DELAY + " ms");
                Thread.sleep(ClientMain.ERROR_RESTART_DELAY);
            }
        }
    }

    private Invocation.Builder createWebRequest(String path) {
        /*JacksonJsonProvider jackson_json_provider = new JacksonJaxbJsonProvider();
        ObjectMapper objectMapper = jackson_json_provider.locateMapper(Object.class, MediaType.APPLICATION_JSON_TYPE);
        objectMapper.registerModule(new JavaTimeModule());*/

        Client client = ClientBuilder.newClient(webClientConfig)
                .register(JacksonFeature.class)
                .register(JacksonObjectMapperProvider.class);
        client.property(ClientProperties.CONNECT_TIMEOUT, TIMEOUT);
        client.property(ClientProperties.READ_TIMEOUT, TIMEOUT);
        return client.target(apiUrl)
                .path(path)
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .header("clientId", clientId)
                .header("Authorization", "BASIC " +
                        DatatypeConverter.printBase64Binary(("apiuser:" + apiKey).getBytes(StandardCharsets.UTF_8)));
    }

    private void updateStatus(StatusEvent event) throws IOException {
        checkResponse(createWebRequest("/event/updateStatus")
                .post(Entity.json(event)));
    }

    private void updateError(ErrorEvent event) throws IOException {
        checkResponse(createWebRequest("/event/exit/error")
                .post(Entity.json(event)));
    }

    private void updatePasswordFound(PasswordFoundEvent event) throws IOException {
        final String password = event.getPassword();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Files.write(Paths.get("log-found.txt"),
                            (Instant.now().toString() + ": PASSWORD FOUND: \"" + event.getPassword() + "\"\r\n")
                                    .getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
        checkResponse(createWebRequest("/event/exit/found")
                .post(Entity.json(event)));
    }

    private void updatePasswordNotFound(PasswordNotFoundEvent event) throws IOException {
        checkResponse(createWebRequest("/event/exit/notFound")
                .post(Entity.json(event)));
    }

    private void checkResponse(Response response) throws IOException {
        if(response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            throw new UnsuccessfulResponseException(response.toString());
        }
    }

    private WordlistAssignment getAssignment() throws IOException {
        Response response = createWebRequest("/getTask").post(Entity.text(""));
        checkResponse(response);
        return response.readEntity(WordlistAssignment.class);
    }

    public static class UnsuccessfulResponseException extends IOException {
        public UnsuccessfulResponseException(String s) {
            super(s);
        }
    }
}
