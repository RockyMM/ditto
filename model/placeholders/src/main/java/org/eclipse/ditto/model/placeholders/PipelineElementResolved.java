/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.model.placeholders;

import java.util.Collections;
import java.util.Iterator;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.concurrent.Immutable;

/**
 * Pipeline elements containing a resolved value.
 */
@Immutable
final class PipelineElementResolved implements PipelineElement {

    private final String value;

    private PipelineElementResolved(final String value) {
        this.value = value;
    }

    static PipelineElement of(final String value) {
        return new PipelineElementResolved(value);
    }

    @Override
    public PipelineElement onResolved(final Function<String, PipelineElement> stringProcessor) {
        return stringProcessor.apply(value);
    }

    @Override
    public PipelineElement onUnresolved(final Supplier<PipelineElement> nextPipelineElement) {
        return this;
    }

    @Override
    public PipelineElement onDeleted(final Supplier<PipelineElement> nextPipelineElement) {
        return this;
    }

    @Override
    public <T> T accept(final PipelineElementVisitor<T> visitor) {
        return visitor.resolved(value);
    }

    @Override
    public Iterator<String> iterator() {
        return Collections.singletonList(value).iterator();
    }
}
