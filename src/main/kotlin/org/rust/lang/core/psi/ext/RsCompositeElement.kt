package org.rust.lang.core.psi.ext

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.util.PsiTreeUtil
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.cargoWorkspace
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsModDeclItem
import org.rust.lang.core.resolve.ref.RsReference

interface RsCompositeElement : PsiElement {
    override fun getReference(): RsReference?
}

val RsCompositeElement.containingMod: RsMod?
    get() = PsiTreeUtil.getStubOrPsiParentOfType(this, RsMod::class.java)

val RsModDeclItem.containingMod: RsMod
    get() = (this as RsCompositeElement).containingMod
        ?: error("Rust mod decl outside of a module")

val RsCompositeElement.crateRoot: RsMod? get() {
    val mod = containingFile as? RsFile ?: return null

    val root = mod.superMods.lastOrNull()
    return if (root != null && root.isCrateRoot)
        root
    else
        null
}

val RsCompositeElement.containingCargoTarget: CargoWorkspace.Target? get() {
    val cargoProject = module?.cargoWorkspace ?: return null
    val crateRoot = crateRoot ?: return null
    return cargoProject.findTargetForCrateRootFile(crateRoot.containingFile.virtualFile)
}

abstract class RsCompositeElementImpl(node: ASTNode) : ASTWrapperPsiElement(node), RsCompositeElement {
    override fun getReference(): RsReference? = null
}

abstract class RsStubbedElementImpl<StubT : StubElement<*>> : StubBasedPsiElementBase<StubT>, RsCompositeElement {

    constructor(node: ASTNode) : super(node)

    constructor(stub: StubT, nodeType: IStubElementType<*, *>) : super(stub, nodeType)

    override fun getReference(): RsReference? = null

    override fun toString(): String = "${javaClass.simpleName}($elementType)"

    // BACKCOMPAT: 2016.2
    // 2016.3 has shiny green stubs
    abstract class WithParent<StubT : StubElement<*>> : RsStubbedElementImpl<StubT> {
        constructor(node: ASTNode) : super(node)

        constructor(stub: StubT, nodeType: IStubElementType<*, *>) : super(stub, nodeType)

        override fun getParent(): PsiElement = parentByStub
    }
}
