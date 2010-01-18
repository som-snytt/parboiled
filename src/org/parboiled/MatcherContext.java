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

package org.parboiled;

import org.jetbrains.annotations.NotNull;
import org.parboiled.common.ImmutableList;
import org.parboiled.common.Preconditions;
import org.parboiled.matchers.*;
import org.parboiled.support.*;
import static org.parboiled.support.ParseTreeUtils.findNode;
import static org.parboiled.support.ParseTreeUtils.findNodeByPath;
import org.parboiled.exceptions.ParserRuntimeException;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>The Context implementation orchestrating most of the matching process.</p>
 * <p>The parsing process works as following:</br>
 * After the rule tree (which is in fact a directed and potentially even cyclic graph of Matcher instances) has been
 * created a root MatcherContext is instantiated for the root rule (Matcher).
 * A subsequent call to {@link #runMatcher(Matcher, boolean)} starts the parsing process.</p>
 * <p>The MatcherContext essentially calls {@link Matcher#match(MatcherContext, boolean)} passing itself to the Matcher
 * which executes its logic, potentially calling sub matchers. For each sub matcher the matcher calls
 * {@link #runMatcher(org.parboiled.matchers.Matcher, boolean)} on its Context, which creates a sub context of the
 * current MatcherContext and runs the given sub matcher in it.</p>
 * <p>This basically creates a stack of MatcherContexts, each corresponding to their rule matchers. The MatcherContext
 * instances serve as a kind of companion objects to the matchers, providing them with support for building the
 * parse tree nodes, keeping track of input locations and error recovery.</p>
 * <p>At each point during the parsing process the matchers and action methods have access to the current MatcherContext
 * and all "open" parent MatcherContexts through the {@link #getParent()} chain.</p>
 *
 * @param <V> the node value type
 */
public class MatcherContext<V> implements Context<V> {

    // small helper class encapsulating all objects that do not change across Context levels
    private static class Invariables<V> {
        private final InputBuffer inputBuffer;
        private final List<ParseError> parseErrors;
        private final Reference<Node<V>> lastNodeRef;

        private Invariables(@NotNull InputBuffer inputBuffer, @NotNull List<ParseError> parseErrors,
                            @NotNull Reference<Node<V>> lastNodeRef) {
            this.inputBuffer = inputBuffer;
            this.parseErrors = parseErrors;
            this.lastNodeRef = lastNodeRef;
        }
    }

    private final Invariables<V> invariables;
    private final MatcherContext<V> parent;
    private final InputLocation startLocation;
    private final Matcher<V> matcher;

    private MatcherContext<V> subContext;
    private InputLocation currentLocation;
    private Node<V> node;
    private List<Node<V>> subNodes;
    private String errorMessage;
    private V nodeValue;
    private Object tag;

    public MatcherContext(@NotNull InputBuffer inputBuffer, @NotNull InputLocation startLocation,
                          @NotNull Matcher<V> matcher, @NotNull List<ParseError> parseErrors) {
        this(null, new Invariables<V>(inputBuffer, parseErrors, new Reference<Node<V>>()), startLocation, matcher);
    }

    private MatcherContext(MatcherContext<V> parent, @NotNull Invariables<V> invariables,
                           @NotNull InputLocation startLocation, @NotNull Matcher<V> matcher) {

        this.parent = parent;
        this.startLocation = currentLocation = startLocation;
        this.matcher = matcher;
        this.invariables = invariables;
    }

    @Override
    public String toString() {
        return getPath();
    }

    //////////////////////////////// CONTEXT INTERFACE ////////////////////////////////////

    public MatcherContext<V> getParent() {
        return parent;
    }

    public MatcherContext<V> getSubContext() {
        return subContext;
    }

    public InputBuffer getInputBuffer() {
        return invariables.inputBuffer;
    }

    @NotNull
    public InputLocation getStartLocation() {
        return startLocation;
    }

    @NotNull
    public Matcher<V> getMatcher() {
        return matcher;
    }

    @NotNull
    public List<ParseError> getParseErrors() {
        return invariables.parseErrors;
    }

    @NotNull
    public InputLocation getCurrentLocation() {
        return currentLocation;
    }

    public String getNodeText(Node<?> node) {
        return ParseTreeUtils.getNodeText(node, invariables.inputBuffer);
    }

    public Character getNodeChar(Node<?> node) {
        return ParseTreeUtils.getNodeChar(node, invariables.inputBuffer);
    }

    @NotNull
    public String getPath() {
        return (parent == null ? "" : parent.getPath()) + '/' + matcher.getLabel();
    }

    public V getNodeValue() {
        return nodeValue;
    }

    public void setNodeValue(V value) {
        this.nodeValue = value;
    }

    public V getTreeValue() {
        V treeValue = nodeValue;
        if (subNodes != null) {
            int i = subNodes.size();
            while (treeValue == null && i-- > 0) {
                treeValue = subNodes.get(i).getValue();
            }
        }
        return treeValue;
    }

    public Node<V> getNodeByPath(String path) {
        return findNodeByPath(subNodes, path);
    }

    public Node<V> getNodeByLabel(String labelPrefix) {
        return subNodes != null ? findNode(subNodes, new LabelPrefixPredicate<V>(labelPrefix)) : null;
    }

    public Node<V> getLastNode() {
        return invariables.lastNodeRef.getTarget();
    }

    public List<Node<V>> getSubNodes() {
        return subNodes != null ? ImmutableList.copyOf(subNodes) : ImmutableList.<Node<V>>of();
    }

    //////////////////////////////// PUBLIC ////////////////////////////////////

    public MatcherContext<V> createCopy(MatcherContext<V> parent, Matcher<V> matcher) {
        return new MatcherContext<V>(parent, invariables, currentLocation, matcher);
    }

    public void setCurrentLocation(InputLocation currentLocation) {
        this.currentLocation = currentLocation;
    }

    public void advanceInputLocation() {
        setCurrentLocation(getCurrentLocation().advance(invariables.inputBuffer));
    }

    public Node<V> getNode() {
        return node;
    }

    public Object getTag() {
        return tag;
    }

    public void setTag(Object tag) {
        this.tag = tag;
    }

    public void addUnexpectedInputError(char illegalChar, @NotNull String expected) {
        addError(new StringBuilder()
                .append("Invalid input ").append(illegalChar != Chars.EOI ? "\'" + illegalChar + '\'' : "EOI")
                .append(", expected ").append(expected)
                .append(ParseError.createMessageSuffix(invariables.inputBuffer, startLocation, currentLocation))
                .toString());
    }

    public void addError(@NotNull String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void createNode() {
        node = new NodeImpl<V>(matcher.getLabel(), subNodes, startLocation, currentLocation, getTreeValue());
        if (!(ProxyMatcher.unwrap(matcher) instanceof TestMatcher)) { // special case: TestMatchers do not add nodes
            if (parent != null) parent.addChildNode(node);
            invariables.lastNodeRef.setTarget(node);
        }
    }

    public void addChildNode(@NotNull Node<V> node) {
        if (subNodes == null) subNodes = new ArrayList<Node<V>>();
        subNodes.add(node);
    }

    /**
     * Runs the given matcher in a sub context (if matcher != null) or the matcher of this context directly on this
     * context (matcher == null).
     *
     * @param matcher  the matcher to run or null, if the matcher of this context is to be run
     * @param enforced true if enforced
     * @return true if matched
     */
    public boolean runMatcher(Matcher<V> matcher, boolean enforced) {
        MatcherContext<V> oldSubContext = null, runContext;
        if (matcher != null) {
            // special case: ActionMatchers need no sub context and no error recovery
            if (ProxyMatcher.unwrap(matcher) instanceof ActionMatcher) {
                try {
                    return matcher.match(this, enforced);
                } catch (Throwable e) {
                    throw new ParserRuntimeException(e,
                            "Error during execution of parsing action '%s/%s' at input position%s", getPath(), matcher,
                            ParseError.createMessageSuffix(invariables.inputBuffer, currentLocation, currentLocation));
                }
            }

            // we execute the given matcher in a new sub context and store this sub context instance as a field
            // in rare cases (error recovery) we might be recursing back into ourselves
            // so we need to save and restore it
            oldSubContext = subContext;
            runContext = subContext = createCopy(this, matcher);
        } else {
            runContext = this;
        }

        boolean matched;
        try {
            matched = runContext.matcher.match(runContext, enforced);
            if (!matched && enforced) {
                runContext.recover();
                matched = true;
            }
        } catch (ParserRuntimeException e) {
            throw e; // don't wrap, just bubble up
        } catch (Throwable e) {
            throw new ParserRuntimeException(e, "Error during execution of parsing rule '%s' at input position%s",
                    getPath(), ParseError.createMessageSuffix(invariables.inputBuffer, runContext.currentLocation,
                            runContext.currentLocation));
        }
        if (runContext.errorMessage != null) {
            runContext.addParserError(ParseError.create(runContext, runContext.node, runContext.errorMessage));
        }

        if (matcher != null) {
            if (matched) {
                setCurrentLocation(subContext.getCurrentLocation());
            }
            subContext = oldSubContext;
        }
        return matched;
    }

    @SuppressWarnings({"unchecked"})
    public Characters getFollowerChars() {
        Characters chars = Characters.NONE;
        MatcherContext<V> parent = this.parent;
        while (parent != null) {
            if (ProxyMatcher.unwrap(parent.getMatcher()) instanceof FollowMatcher) {
                FollowMatcher<V> followMatcher = (FollowMatcher<V>) parent.getMatcher();
                chars = chars.add(followMatcher.getFollowerChars(parent));
                if (!chars.contains(Chars.EMPTY)) return chars;
            }
            parent = parent.parent;
        }
        return chars.remove(Chars.EMPTY).add(Chars.EOI);
    }

    public boolean inPredicate() {
        return ProxyMatcher.unwrap(matcher) instanceof TestMatcher || parent != null && parent.inPredicate();
    }

    //////////////////////////////// PRIVATE ////////////////////////////////////

    private void recover() {
        if (trySingleSymbolDeletion()) return;

        Characters followerChars = getFollowerChars();
        if (trySingleSymbolInsertion(followerChars)) return;
        resynchronize(followerChars);
    }

    // check whether the current char is a junk char that we can simply discard to continue with the next char
    private boolean trySingleSymbolDeletion() {
        Characters starterChars = matcher.getStarterChars();
        Preconditions.checkState(!starterChars.contains(Chars.EMPTY));
        char lookAheadOne = getCurrentLocation().lookAhead(invariables.inputBuffer, 1);
        if (!starterChars.contains(lookAheadOne)) {
            return false;
        }

        // normally, we need to run the IllegalCharactersMatcher in our parent context so the created node
        // appears on the same tree level, however if we are the root ourselves we run in this context
        MatcherContext<V> parentContext = parent != null ? parent : this;

        // success, we have to skip only one char in order to be able to start the match
        // match the illegal char and createActions a node for it
        IllegalCharactersMatcher<V> illegalCharsMatcher =
                new IllegalCharactersMatcher<V>(matcher.getExpectedString(), Characters.of(lookAheadOne));
        parentContext.runMatcher(illegalCharsMatcher, true);

        // retry the original match
        parentContext.runMatcher(matcher, true);

        // catch up with the advanced location
        setCurrentLocation(parentContext.getCurrentLocation());

        return true;
    }

    // check whether the current char is a legally following next char in the follower set
    // if so, just virtually "insert" the missing expected token and continue
    private boolean trySingleSymbolInsertion(Characters followerChars) {
        char currentChar = getCurrentLocation().currentChar;
        if (!followerChars.contains(currentChar)) return false;

        // success, the current mismatching token is a legal follower,
        // so add a ParseError and still "match" (empty)
        addUnexpectedInputError(currentChar, matcher.getExpectedString());
        createNode();
        return true;
    }

    // consume all characters until we see a legal follower
    private void resynchronize(Characters followerChars) {
        createNode(); // createActions an empty match node

        // normally, we need to run the IllegalCharactersMatcher in our parent context so the created node
        // appears on the same tree level, however if we are at the root ourselves we run in this context
        MatcherContext<V> parentContext = parent != null ? parent : this;

        // createActions a node for the illegal chars
        parentContext.runMatcher(new IllegalCharactersMatcher<V>(matcher.getExpectedString(), followerChars), true);

        // catch up with the advanced location
        setCurrentLocation(parentContext.getCurrentLocation());
    }

    private void addParserError(ParseError error) {
        // do not add the error if we already have one at the exact same input location
        for (ParseError parseError : invariables.parseErrors) {
            if (parseError.getErrorStart() == error.getErrorStart()) return;
        }
        invariables.parseErrors.add(error);
    }

}