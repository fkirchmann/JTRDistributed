/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.core;

import com.esotericsoftware.minlog.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

public class LocalTimeLogger extends Log.Logger {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void log (int level, String category, String message, Throwable ex) {
        StringBuilder builder = new StringBuilder(256);

        builder.append(LocalDateTime.now().format(FORMATTER));

        switch (level) {
            case Log.LEVEL_ERROR:
                builder.append(" ERROR: ");
                break;
            case Log.LEVEL_WARN:
                builder.append("  WARN: ");
                break;
            case Log.LEVEL_INFO:
                builder.append("  INFO: ");
                break;
            case Log.LEVEL_DEBUG:
                builder.append(" DEBUG: ");
                break;
            case Log.LEVEL_TRACE:
                builder.append(" TRACE: ");
                break;
        }

        if (category != null) {
            builder.append('[');
            builder.append(category);
            builder.append("] ");
        }

        builder.append(message);

        if (ex != null) {
            StringWriter writer = new StringWriter(256);
            ex.printStackTrace(new PrintWriter(writer));
            builder.append('\n');
            builder.append(writer.toString().trim());
        }

        print(builder.toString());
    }

    protected void print (String message) {
        System.out.println(message);
    }
}
