/*
 * Copyright (c) 2022-2023 Felix Kirchmann.
 * Distributed under the MIT License (license terms are at http://opensource.org/licenses/MIT).
 *
 * NOTE: This was developed as a one-off - the code still needs a few days of love and refactoring to be properly usable.
 */

package jtr.distributed.server.web.util;

import com.esotericsoftware.minlog.Log;
import jtr.distributed.server.ServerMain;
import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;

import javax.ws.rs.NotFoundException;

public class ExceptionLogger implements ApplicationEventListener, RequestEventListener {

    @Override
    public void onEvent(final ApplicationEvent applicationEvent) {
    }

    @Override
    public RequestEventListener onRequest(final RequestEvent requestEvent) {
        return this;
    }

    @Override
    public void onEvent(RequestEvent paramRequestEvent) {
        if(paramRequestEvent.getType() == RequestEvent.Type.ON_EXCEPTION) {
            if(paramRequestEvent.getException() instanceof NotFoundException
                    && paramRequestEvent.getException().getCause() == null) {
                return; // Don't log 404 errors that aren't the result of an application exception
            }
            if(paramRequestEvent.getException() instanceof ServerMain.ClientNotFoundException) {
                Log.info("Web server", "Rejected request from unknown client \"" +
                        ((ServerMain.ClientNotFoundException) paramRequestEvent.getException()).getClientId() + "\"");
            } else {
                Log.warn("Web server", paramRequestEvent.getException().getCause());
            }
        }
    }
}