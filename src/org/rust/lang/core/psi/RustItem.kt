package org.rust.lang.core.psi

public interface RustItem : RustNamedElement, RustDeclaringElement {

    val attrs : List<RustOuterAttr>?
        get

    val vis: RustVis?
        get

    fun isPublic(): Boolean {
        return vis != null;
    }
}
