/*
 * (C) Copyright 2014-2015 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Julien Carsique
 *
 */

package org.nuxeo.runtime.test;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import junit.framework.AssertionFailedError;

/**
 * Utility class for working with {@link org.junit.runner.Result#getFailures()}
 *
 * @since 5.9.5
 */
public class Failures {

    private static final Logger log = LogManager.getLogger(Failures.class);

    private List<Failure> failures;

    public Failures(List<Failure> failures) {
        this.failures = failures;
    }

    public Failures(Result result) {
        failures = result.getFailures();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        AssertionFailedError errors = new AssertionFailedError();
        for (Failure failure : failures) {
            sb.append("* Failure ")
              .append(i)
              .append(": ")
              .append(failure.getTestHeader())
              .append("\n")
              .append(failure.getTrace())
              .append("\n");
            errors.addSuppressed(failure.getException());
            i++;
        }
        if (errors.getSuppressed().length > 0) {
            // Log because JUnit swallows some parts of the stack trace
            log.debug(errors.getMessage(), errors);
        }
        return sb.toString();
    }

    /**
     * Call {@link org.junit.Assert#fail(String)} with a nice expanded string if there are failures. It also replaces
     * original failure messages with a custom one if originalMessage is not {@code null}.
     *
     * @param originalMessage Message to replace if found in a failure
     * @param customMessage Custom message to use as replacement for originalMessage
     */
    public void fail(String originalMessage, String customMessage) {
        if (failures.isEmpty()) {
            Assert.fail(customMessage);
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        AssertionFailedError errors = new AssertionFailedError(customMessage);
        sb.append(customMessage);
        for (Failure failure : failures) {
            sb.append("\n* Failure ").append(i).append(": ");
            String trace = failure.getTrace();
            if (originalMessage != null && originalMessage.equals(failure.getMessage())) {
                trace = trace.replaceAll(originalMessage, customMessage);
            }
            sb.append(failure.getTestHeader()).append("\n").append(trace);
            errors.addSuppressed(failure.getException());
            i++;
        }
        // Log because JUnit swallows some parts of the stack trace
        log.debug(errors.getMessage(), errors);
        Assert.fail(sb.toString());
    }
}
