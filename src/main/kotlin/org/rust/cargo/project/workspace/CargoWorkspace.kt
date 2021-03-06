package org.rust.cargo.project.workspace

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import org.rust.cargo.toolchain.impl.CleanCargoMetadata
import java.util.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Rust project model represented roughly in the same way as in Cargo itself.
 *
 * [CargoProjectWorkspaceService] is responsible for providing a [CargoWorkspace] for
 * an IDEA module.
 */
class CargoWorkspace private constructor(
    val packages: Collection<Package>
) {

    class Package(
        val contentRootUrl: String,
        val name: String,
        val version: String,
        val targets: Collection<Target>,
        val source: String?,
        val origin: PackageOrigin
    ) {
        val libTarget: Target? get() = targets.find { it.isLib }
        val contentRoot: VirtualFile? get() = VirtualFileManager.getInstance().findFileByUrl(contentRootUrl)

        override fun toString() = "Package(contentRootUrl='$contentRootUrl', name='$name')"

        fun initTargets(): Package {
            for (it in targets) {
                it.initPackage(this)
            }
            return this
        }
    }

    class Target(
        /**
         * Absolute path to the crate root file
         */
        val crateRootUrl: String,
        val name: String,
        val kind: TargetKind
    ) {
        // target name must be a valid Rust identifier, so normalize it by mapping `-` to `_`
        // https://github.com/rust-lang/cargo/blob/ece4e963a3054cdd078a46449ef0270b88f74d45/src/cargo/core/manifest.rs#L299
        val normName = name.replace('-', '_')
        val isLib: Boolean get() = kind == TargetKind.LIB
        val isBin: Boolean get() = kind == TargetKind.BIN
        val isExample: Boolean get() = kind == TargetKind.EXAMPLE

        private val crateRootCache = AtomicReference<VirtualFile>()
        val crateRoot: VirtualFile? get() {
            val cached = crateRootCache.get()
            if (cached != null && cached.isValid) return cached
            val file = VirtualFileManager.getInstance().findFileByUrl(crateRootUrl)
            crateRootCache.set(file)
            return file
        }

        private lateinit var myPackage: Package
        fun initPackage(pkg: Package) {
            myPackage = pkg
        }

        val pkg: Package get() = myPackage

        override fun toString(): String
            = "Target(crateRootUrl='$crateRootUrl', name='$name', kind=$kind)"
    }

    enum class TargetKind {
        LIB, BIN, TEST, EXAMPLE, BENCH, UNKNOWN
    }

    private val targetByCrateRootUrl = packages.flatMap { it.targets }.associateBy { it.crateRootUrl }

    fun findCrateByName(normName: String): Target? =
        packages
            .mapNotNull { it.libTarget }
            .find { it.normName == normName }

    /**
     * If the [file] is a crate root, returns the corresponding [Target]
     */
    fun findTargetForCrateRootFile(file: VirtualFile): Target? {
        val canonicalFile = file.canonicalFile ?: return null
        return targetByCrateRootUrl[canonicalFile.url]
    }

    fun findPackage(name: String): Package? = packages.find { it.name == name }

    fun isCrateRoot(file: VirtualFile): Boolean = findTargetForCrateRootFile(file) != null

    fun withStdlib(libs: List<StandardLibraryRoots.StdCrate>): CargoWorkspace {
        val stdlib = libs.map { crate ->
            Package(
                contentRootUrl = crate.packageRootUrl,
                name = crate.name,
                version = "",
                targets = listOf(Target(crate.crateRootUrl, name = crate.name, kind = TargetKind.LIB)),
                source = null,
                origin = PackageOrigin.STDLIB
            ).initTargets()
        }
        return CargoWorkspace(packages + stdlib)
    }

    val hasStandardLibrary: Boolean get() = packages.any { it.origin == PackageOrigin.STDLIB }

    companion object {
        fun deserialize(data: CleanCargoMetadata): CargoWorkspace {
            // Packages form mostly a DAG. "Why mostly?", you say.
            // Well, a dev-dependency `X` of package `P` can depend on the `P` itself.
            // This is ok, because cargo can compile `P` (without `X`, because dev-deps
            // are used only for tests), then `X`, and then `P`s tests. So we need to
            // handle cycles here.

            // Figure out packages origins:
            // - if a package is a workspace member, it's WORKSPACE
            // - if a package is a direct dependency of a workspace member, it's DEPENDENCY
            // - otherwise, it's TRANSITIVE_DEPENDENCY
            val nameToOrigin = HashMap<String, PackageOrigin>(data.packages.size)
            data.packages.forEachIndexed pkgs@ { index, pkg ->
                if (pkg.isWorkspaceMember) {
                    nameToOrigin[pkg.name] = PackageOrigin.WORKSPACE
                    val depNode = data.dependencies.getOrNull(index) ?: return@pkgs
                    depNode.dependenciesIndexes
                        .mapNotNull { data.packages.getOrNull(it) }
                        .forEach {
                            nameToOrigin.merge(it.name, PackageOrigin.DEPENDENCY, { o1, o2 -> PackageOrigin.min(o1, o2) })
                        }
                } else {
                    nameToOrigin.putIfAbsent(pkg.name, PackageOrigin.TRANSITIVE_DEPENDENCY)
                }
            }

            val packages = data.packages.map { pkg ->
                val origin = nameToOrigin[pkg.name] ?: error("Origin is undefined for package ${pkg.name}")
                Package(
                    pkg.url,
                    pkg.name,
                    pkg.version,
                    pkg.targets.map { Target(it.url, it.name, it.kind) },
                    pkg.source,
                    origin
                ).initTargets()
            }

            return CargoWorkspace(packages)
        }
    }
}

