/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.client.john;

import com.esotericsoftware.minlog.Log;
import jtr.distributed.core.wordlist.WordlistGenerator;
import jtr.distributed.core.events.*;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.Synchronized;

import java.io.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JTRunner {
    private static final String[] args = new String[]{
            "--stdin", "--progress-every=4", "--format=ZIP-opencl",
    };

    private static final Pattern STATUSLINE_PATTERN = Pattern.compile(
            "^[0-9]+g.+ ([0-9]+[A-Z]?)p/s.* ([^ ]+)\\.\\.[^ ]+$");
    private static final Pattern PASSWORD_FOUND_PATTERN = Pattern.compile(
            "^([^ ]+) \\([^()]+\\)$");
    private static final Pattern GPU_MODEL_PATTERN = Pattern.compile(
            "^Device [^: ]+: (.+)$");

    private static final String LOG_JTR = "jtr", LOG_JTR_STDOUT = "jtr-stdout", LOG_JTR_STDERR = "jtr-stderr";

    private final Process process;

    private final BlockingQueue<JTEvent> eventBus = new LinkedBlockingQueue<>();

    private final AtomicBoolean exitEventPosted = new AtomicBoolean(false);
    private final AtomicBoolean passwordFound = new AtomicBoolean(false);
    private volatile String gpuModel = null;


    private final Semaphore stdErrReaderComplete = new Semaphore(1, true);

    private void onPasswordFound(String foundPassword) {
        //stop.set(true);
        passwordFound.set(true);
        System.out.println("PW FOUND: " + foundPassword);
        Log.info(LOG_JTR, "!!! PASSWORD FOUND: \"" + foundPassword + "\" !!!");
        postEvent(new PasswordFoundEvent(foundPassword));
    }

    @SneakyThrows
    public JTRunner(final File johnDirectory, String devices, File hashfile,
                    WordlistGenerator wg, long begin, long end, String[] johnExtraArgs) throws IOException {
        if(!johnDirectory.isDirectory()) throw new IOException("JohnDirectory is not a dir");

        List<String> command = new ArrayList();
        command.add(new File(johnDirectory, "john").getPath());
        command.add("--devices=" + devices);
        command.add("--session=device" + devices);
        command.addAll(Arrays.asList(args));
        if(johnExtraArgs.length > 0) { command.addAll(Arrays.asList(johnExtraArgs)); }
        command.add(hashfile.getAbsolutePath());

        //command.add(args)
        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(johnDirectory);
        pb.environment().putAll(System.getenv());
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        process = pb.start();
        final OutputStream os = process.getOutputStream();

        stdErrReaderComplete.acquire();

        Thread stdOutReader = new Thread(() -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    Log.debug(LOG_JTR_STDOUT, line);

                    Matcher passwordFoundMatcher = PASSWORD_FOUND_PATTERN.matcher(line);
                    if(passwordFoundMatcher.matches()) {
                        onPasswordFound(passwordFoundMatcher.group(1));
                    }
                    // Ignore all output on program end, but still consume it to allow it to exit gracefully
                    //if(stop.get()) { continue; }
                }
            }
            catch (IOException e) {
                Log.debug(LOG_JTR, "StdOut reader failed", e);
                //if(!stop.compareAndSet(false, true)) { return; }
                postEvent(new ErrorEvent("StdOut reader failed", e));
                process.destroyForcibly();
            }
            Log.debug(LOG_JTR, "StdOut reader complete, waiting for StdErrReader...");
            try {
                stdErrReaderComplete.acquire();
            } catch (InterruptedException e) {
                postEvent(new ErrorEvent("Interrupted", e));
            }
            Log.debug(LOG_JTR, "StdOut reader complete, posting exit event");
            postEvent(new ErrorEvent("StdOut reader complete", new IllegalStateException("StdOut reader complete")));
        });
        stdOutReader.start();
        Thread stdErrReader = new Thread(() -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    Log.debug(LOG_JTR_STDERR, line);
                    // Ignore all output on program end, but still consume it to allow it to exit gracefully
                    //if(stop.get()) { continue; }
                    Matcher passwordFoundMatcher = PASSWORD_FOUND_PATTERN.matcher(line);
                    if(passwordFoundMatcher.find()) {
                        onPasswordFound(passwordFoundMatcher.group(1));
                    }
                    Matcher matcher = STATUSLINE_PATTERN.matcher(line);
                    if(matcher.find()) {
                        String guessesPerSecondStr = matcher.group(1);
                        String lastGuessedPassword = matcher.group(2);
                        long factor = 1;
                        if(guessesPerSecondStr.endsWith("K")) {
                            factor = 1000;
                            guessesPerSecondStr = guessesPerSecondStr.substring(0, guessesPerSecondStr.length() - 1);
                        }
                        if(guessesPerSecondStr.endsWith("M")) {
                            factor = 1000 * 1000;
                            guessesPerSecondStr = guessesPerSecondStr.substring(0, guessesPerSecondStr.length() - 1);
                        }
                        try {
                            long guessesPerSecond = Long.parseLong(guessesPerSecondStr) * factor;
                            long lastGuessedIndex = wg.indexOf(lastGuessedPassword);
                            double percentProgress = ((double) lastGuessedIndex - begin) / (end - begin) * 100.0;
                            Log.trace(LOG_JTR, String.format("Guessed %d / %d words (%.2f %%) @ %d p/s, last: %s%n",
                                    lastGuessedIndex - begin, end - begin, percentProgress, guessesPerSecond,
                                    lastGuessedPassword));
                            postEvent(new StatusEvent(guessesPerSecond, begin, lastGuessedIndex, end,
                                    lastGuessedPassword, gpuModel, Instant.now()));
                        } catch (IllegalArgumentException e) {
                            Log.warn(LOG_JTR, "Warning: Could not parse stdErr line!");
                            e.printStackTrace();
                        }
                    }
                    else if(line.contains("option to display all of the cracked passwords reliably")) {
                        passwordFound.set(true);
                    } else if(line.contains("Session completed") && !passwordFound.get()) {
                        //stop.set(true);
                        Log.info(LOG_JTR, "--- Password not found ---");
                        postEvent(new PasswordNotFoundEvent(begin, end));
                    } else if((matcher = GPU_MODEL_PATTERN.matcher(line)).find()) {
                        gpuModel = matcher.group(1);
                    }
                }
            }
            catch (IOException e) {
                Log.debug(LOG_JTR, "StdErr reader failed", e);
                //if(!stop.compareAndSet(false, true)) { return; }
                //postEvent(new ErrorEvent("StdOut reader failed", e));
                //process.destroyForcibly();
            }
            Log.debug(LOG_JTR, "StdErr reader complete");
            stdErrReaderComplete.release();
        });
        stdErrReader.start();

        Thread t = new Thread(() -> {
            try {
                wg.outputWords(begin, end, os);
                Log.debug(LOG_JTR, "Closing StdIn wordlist stream");
                try {
                    os.flush();
                    os.close();
                } catch (IOException ignored) {}
                Log.debug(LOG_JTR, "Waiting for exit");
                Log.debug(LOG_JTR, "Exit code: " + process.waitFor());
            }
            catch (IOException | InterruptedException e) {
                //if(!stop.compareAndSet(false, true)) { return; }
                Log.debug(LOG_JTR, "StdIn writer failed", e);
                //postEvent(new ErrorEvent("StdIn writer failed", e));
                //process.destroyForcibly();
            }
            Log.debug(LOG_JTR, "StdIn writer complete");
        });
        t.start();
    }

    @SneakyThrows
    private void postEvent(JTEvent event) {
        if(event instanceof ExitEvent && exitEventPosted.getAndSet(true)) {
            Log.debug(LOG_JTR, "Ignoring ExitEvent " + event + " due to previously posted one");
            return;
        }
        eventBus.put(event);
    }

    @Synchronized
    public JTEvent takeNextEvent() throws InterruptedException {
        JTEvent event = eventBus.take();
        // If several StatusEvents have queued up, ignore all except the most recent one.
        while(event instanceof StatusEvent && eventBus.peek() instanceof StatusEvent) {
            event = eventBus.poll();
        }
        return event;
    }

    public void kill() {
        process.destroyForcibly();
    }
}
