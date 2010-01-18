/*
 * Copyright (C) 2009 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.parboiled.actionparameters;

import org.jetbrains.annotations.NotNull;
import org.parboiled.MatcherContext;
import org.parboiled.Node;

/**
 * An ActionParameter that evaluates to the value set on the parse tree node passed in as argument.
 */
public class ValueParameter implements ActionParameter{

    private final Object node;

    public ValueParameter(Object node) {
        this.node = node;
    }

    public Object resolve(@NotNull MatcherContext<?> context) throws Throwable {
        Node node = ActionParameterUtils.resolve(this.node, context, Node.class);
        return node == null ? null : node.getValue();
    }

    @Override
    public String toString() {
        return "VALUE(" + node + ')';
    }

}