// Where a spec comes from: one JSON file, or a directory of pieces that are one spec.
//
// A flow's screens are written from a contract document (decision 2026-09-09, UI D1/D2/E3),
// and that document carries the flow's part of the spec in a fenced `json spfn-ui` block at
// its end. So the generator's input stopped being a file and became a PLACE: the JSON beside
// the documents holds the flows nobody writes a document for, each document holds its own,
// and what the emitters read is the union.
//
// Two properties are what make the union safe to read, and both are refusals rather than
// merges. A piece is read WHOLE by `Spec.read` — the same reader, not a second copy of it —
// so a document with a broken block fails as a spec fails and names its own path. And a name
// declared twice is refused rather than resolved: one flow lives in one place, so there is
// never a question of which of two documents describes the screens on the phone.
//
// The digest is the third: everything a generated header prints has to be a pure function of
// what was read, and a directory walked in the OS's order is not one. The pieces are sorted
// by name and framed with it, so the same files hash the same on every host.

package xyz.superfunction.spfn.uicodegen

import java.io.File
import java.security.MessageDigest
import xyz.superfunction.spfn.codegen.Bundle

/** The spec that was read, and the digest every generated header prints for it. */
data class SpecSource(val spec: Spec, val sha256: String)

/** One file the spec is made of, by the two names it is known by and its bytes. */
private class Piece(
    /** Repository-relative, for a refusal to name. */
    val path: String,

    /** Relative to the spec directory, which is what the digest is framed with. */
    val name: String,

    val bytes: ByteArray
)

/** One piece as the reader understood it, kept beside the path it was read from. */
private class ReadPiece(val path: String, val spec: Spec)

object SpecInput
{
    /** The name a directory spec's JSON pieces are looked for by. */
    private const val DOCUMENTS: String = "contracts";

    /**
     * The spec at [specPath], which is either one JSON file or a directory of pieces.
     *
     * A file is read as it always was. A directory is every `.json` file beside it and every
     * `.md` document under `contracts`, each read whole and then merged — see [merge] for
     * what a merge refuses.
     */
    fun read(repoRoot: File, specPath: String, bundle: Bundle): SpecSource
    {
        val pieces = pieces(repoRoot, specPath);
        val read = pieces.map { piece ->
            ReadPiece(piece.path, Spec.read(text(piece), bundle)).also { checkViewSource(it, piece) }
        };
        return SpecSource(merge(read), digest(pieces));
    }

    /**
     * The files [specPath] names, in NAME order.
     *
     * Sorted rather than walked, because `File.listFiles` answers in whatever order the
     * filesystem holds and the digest below is framed with these names: a directory that
     * hashed differently on a Mac than on the CI runner would make every generated header a
     * fact about the host (P8).
     */
    private fun pieces(repoRoot: File, specPath: String): List<Piece>
    {
        val root = File(repoRoot, specPath);
        if (root.isFile)
        {
            return listOf(Piece(specPath, root.name, root.readBytes()));
        }
        if (!root.isDirectory)
        {
            throw SpecException("missing $specPath");
        }

        val json = (root.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".json") };
        val documents = (File(root, DOCUMENTS).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".md") }
            .map { File("$DOCUMENTS/${it.name}") to it };
        val found = json.map { File(it.name) to it } + documents;
        if (found.isEmpty())
        {
            throw SpecException(
                "$specPath holds no spec: a directory spec is its *.json files and its $DOCUMENTS/*.md documents"
            );
        }
        return found.sortedBy { it.first.path }
            .map { (name, file) -> Piece("$specPath/${name.path}", name.path, file.readBytes()) };
    }

    /** A piece as JSON: a document's machine block, or the whole of a `.json` file. */
    private fun text(piece: Piece): String
    {
        val content = String(piece.bytes, Charsets.UTF_8);
        return if (piece.name.endsWith(".md")) machineBlock(content, piece.path) else content;
    }

    /**
     * The one ```` ```json spfn-ui ```` block of a contract document.
     *
     * A line-by-line state machine and not a regular expression, for the three shapes a
     * document really arrives in: a block whose JSON carries a ``` inside a string, a file
     * checked out with CRLF endings, and a tag written with trailing spaces. A greedy match
     * between the first fence and the last would swallow the document's prose; a lazy one
     * would stop at the first ``` anywhere on any line.
     *
     * Two blocks and none are the same refusal, because they are the same mistake: a document
     * that carries two truths carries none, and one that carries none is a document whose
     * flow the generator would silently not know about (CONTRACT.md).
     */
    fun machineBlock(text: String, path: String): String
    {
        val body = mutableListOf<String>();
        var blocks = 0;
        var open = false;

        text.split("\n").map { it.removeSuffix("\r") }.forEach { line ->
            when
            {
                !open && line.trimEnd() == OPENING_FENCE -> { open = true; blocks += 1 }
                open && line.trimEnd() == CLOSING_FENCE -> open = false
                open && blocks == 1 -> body += line
            };
        };

        if (open)
        {
            throw SpecException("$path opens a spfn-ui block that no closing fence ends");
        }
        if (blocks != 1)
        {
            throw SpecException("$path holds $blocks spfn-ui blocks; one flow is one block");
        }
        return body.joinToString("\n");
    }

    /**
     * Refusal: a flow in a `.json` piece may not claim its views are authored.
     *
     * `authored` says a person wrote these screens from a document that states what they
     * must do, show and not differ in. A JSON file states none of that, so a flow that
     * claimed it there would be asking the generator to leave files alone that nothing in
     * the repository describes.
     */
    private fun checkViewSource(read: ReadPiece, piece: Piece)
    {
        if (piece.name.endsWith(".md"))
        {
            return;
        }
        read.spec.flows.filter { it.authored }.forEach { flow ->
            throw SpecException(
                "flows.${flow.name}.views is '${FlowDefinition.AUTHORED}' in ${read.path}; a view written by " +
                    "hand is written from a contract document, so a flow whose views are authored lives in one"
            );
        };
    }

    /**
     * The pieces as one spec, or a refusal naming the two paths that disagree.
     *
     * Four rules, and every one of them is about a name meaning one thing. The version and
     * the pinned digest are properties of the whole spec, so pieces that disagree about
     * either were written against different generators or different contract bundles. A
     * service method two pieces both declare has to be the same method, because each piece
     * is read whole and a piece that calls a method must therefore declare it. And a flow or
     * a screen declared twice is refused outright: one flow lives in one place.
     */
    private fun merge(pieces: List<ReadPiece>): Spec
    {
        val first = pieces.first();
        pieces.drop(1).forEach { piece -> checkAgreement(first, piece) };
        return Spec(
            specVersion = first.spec.specVersion,
            manifestSha256 = first.spec.manifestSha256,
            services = mergeServices(pieces),
            flows = unique(pieces, "flows", { it.flows }, { it.name }),
            screens = unique(pieces, "screens", { it.screens }, { it.name })
        );
    }

    /** The two fields every piece of one spec states identically, or a refusal. */
    private fun checkAgreement(first: ReadPiece, piece: ReadPiece)
    {
        if (first.spec.specVersion != piece.spec.specVersion)
        {
            throw SpecException(
                "specVersion is ${first.spec.specVersion} in ${first.path} and ${piece.spec.specVersion} in " +
                    "${piece.path}; the pieces of one spec are written for one generator"
            );
        }
        if (first.spec.manifestSha256 != piece.spec.manifestSha256)
        {
            throw SpecException(
                "contract.manifestSha256 is ${first.spec.manifestSha256} in ${first.path} and " +
                    "${piece.spec.manifestSha256} in ${piece.path}; the pieces of one spec are written against " +
                    "one contract bundle"
            );
        }
    }

    /**
     * Every piece's [of], refused when two pieces declare the same [name].
     *
     * [kind] is the spec path the message names the collision by, so the refusal reads as
     * the key an author can search both files for.
     */
    private fun <T> unique(
        pieces: List<ReadPiece>,
        kind: String,
        of: (Spec) -> List<T>,
        name: (T) -> String
    ): List<T>
    {
        val declaredIn = mutableMapOf<String, String>();
        val merged = mutableListOf<T>();
        pieces.forEach { piece ->
            of(piece.spec).forEach { item ->
                val already = declaredIn.put(name(item), piece.path);
                if (already != null)
                {
                    throw SpecException(
                        "$kind.${name(item)} is declared in both $already and ${piece.path}; one flow lives in " +
                            "one place, and its screens live with it"
                    );
                }
                merged += item;
            };
        };
        return merged.sortedBy(name);
    }

    /**
     * The services of every piece, merged by method.
     *
     * A union rather than a uniqueness rule, because a piece is read whole: a document whose
     * screen calls `deviceApproval.approve` has to declare that method, and so does every
     * other piece with a screen that calls it. What is refused is the same method naming two
     * different operations — the one case where a merge would have to choose.
     */
    private fun mergeServices(pieces: List<ReadPiece>): List<ServiceDefinition>
    {
        val methods = mutableMapOf<String, ServiceMethod>();
        val declaredIn = mutableMapOf<String, String>();
        pieces.forEach { piece ->
            piece.spec.services.flatMap { it.methods }.forEach { method ->
                val already = methods[method.reference];
                if (already != null && already.operation != method.operation)
                {
                    throw SpecException(
                        "services.${method.reference} names operation '${already.operation}' in " +
                            "${declaredIn.getValue(method.reference)} and '${method.operation}' in " +
                            "${piece.path}; a method two pieces both declare is one method"
                    );
                }
                methods[method.reference] = method;
                declaredIn.putIfAbsent(method.reference, piece.path);
            };
        };
        return methods.values.groupBy { it.service }.toSortedMap()
            .map { (service, declared) -> ServiceDefinition(service, declared.sortedBy { it.name }) };
    }

    /**
     * The digest of the pieces: sha256 over each one's NAME, a newline, its bytes and a
     * newline, in name order.
     *
     * The names are the ones inside the spec directory rather than the repository-relative
     * ones, and that is deliberate. The directory's own path is already an input, printed on
     * every header's `spec:` line, and an input that reached the output through two lines at
     * once would be one no reader could ever watch move alone. What this digest states is a
     * fact about the PIECES: the same documents under another directory hash the same, and a
     * byte changed in any of them — or a piece added, removed or renamed — moves it.
     */
    private fun digest(pieces: List<Piece>): String
    {
        val digest = MessageDigest.getInstance("SHA-256");
        pieces.forEach { piece ->
            digest.update(piece.name.toByteArray(Charsets.UTF_8));
            digest.update('\n'.code.toByte());
            digest.update(piece.bytes);
            digest.update('\n'.code.toByte());
        };

        val digits = "0123456789abcdef";
        val out = StringBuilder();
        digest.digest().forEach { byte ->
            val value = byte.toInt() and 0xFF;
            out.append(digits[value shr 4]);
            out.append(digits[value and 0x0F]);
        };
        return out.toString();
    }

    private const val OPENING_FENCE: String = "```json spfn-ui";

    private const val CLOSING_FENCE: String = "```";
}
