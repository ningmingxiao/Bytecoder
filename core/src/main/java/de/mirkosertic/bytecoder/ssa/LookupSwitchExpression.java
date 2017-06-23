/*
 * Copyright 2017 Mirko Sertic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.mirkosertic.bytecoder.ssa;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LookupSwitchExpression extends Expression implements ExpressionListContainer {

    private final Variable variable;
    private final ExpressionList defaultExpressions;
    private final Map<Long, ExpressionList> pairs;

    public LookupSwitchExpression(Variable aVariable, ExpressionList aDefaultExpressions,
            Map<Long, ExpressionList> aPairs) {
        variable = aVariable.usedBy(this);
        defaultExpressions = aDefaultExpressions;
        pairs = aPairs;
    }

    public Variable getVariable() {
        return variable;
    }

    public ExpressionList getDefaultExpressions() {
        return defaultExpressions;
    }

    public Map<Long, ExpressionList> getPairs() {
        return pairs;
    }

    @Override
    public Set<ExpressionList> getExpressionLists() {
        Set<ExpressionList> theResult = new HashSet<>();
        theResult.add(defaultExpressions);
        theResult.addAll(pairs.values());
        return theResult;
    }
}