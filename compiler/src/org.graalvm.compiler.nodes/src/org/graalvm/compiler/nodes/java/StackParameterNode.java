package org.graalvm.compiler.nodes.java;

import jdk.vm.ci.meta.JavaKind;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.IterableNodeType;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.AbstractLocalNode;

@NodeInfo(nameTemplate = "SP({p#index})")
public class StackParameterNode extends AbstractLocalNode implements IterableNodeType {

    public static final NodeClass<StackParameterNode> TYPE = NodeClass.create(StackParameterNode.class);

    private final int index;

    public StackParameterNode(int index, int actualIndex, JavaKind kind) {
        super(TYPE, actualIndex, StampFactory.forKind(kind));
        this.index = index;
    }

    public int getIndex() {
        return index;
    }
}
