package jml.lsp

import com.github.javaparser.JavaParser
import com.github.javaparser.ParseResult
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.TokenRange
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.tinylog.kotlin.Logger
import java.net.URI
import java.nio.file.Path
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.io.path.toPath

private val com.github.javaparser.Position.asPosition: Position
    get() = Position(line - 1, column - 1)

private val Optional<TokenRange>.asRange: Range
    get() =
        if (isPresent) {
            val r = get().toRange().get()
            Range(r.begin.asPosition, r.end.asPosition)
        } else {
            Range()
        }

class AstRepository(val server: JmlLanguageServer, val executorService: ExecutorService) {
    private val sourceFolders = arrayListOf<Path>()
    val config: ParserConfiguration = ParserConfiguration()
    val typeSolver = CombinedTypeSolver(
        ClassLoaderTypeSolver(ClassLoader.getSystemClassLoader())
    )

    val path2AstWait = mutableMapOf<Path, Future<*>>()
    val path2Ast = mutableMapOf<Path, ParseResult<CompilationUnit>>()


    init {
        config.isProcessJml = true
        config.setSymbolResolver(JavaSymbolSolver(typeSolver))
    }

    fun createJavaParser() = JavaParser(config)

    fun parse(path: Path): Future<ParseResult<CompilationUnit>> {
        val future = executorService.submit(Callable {
            val jp = createJavaParser()
            val result = jp.parse(path)
            if (result.isSuccessful) {
                getSourcePath(result.result.get())?.let { path ->
                    addSourceFolder(path)
                }
            }
            announceResult(path, result)
            result
        })
        synchronized(path2AstWait) {
            path2AstWait[path] = future
        }
        return future
    }

    private fun announceResult(path: Path, result: ParseResult<CompilationUnit>) {
        synchronized(path2Ast) {
            synchronized(path2AstWait) {
                path2AstWait.remove(path)
            }
            path2Ast[path] = result
            if (result.isSuccessful && result.result.isPresent) {
                server.client?.showMessage(MessageParams(MessageType.Log, "$path parsed"))
            } else {
                val diagnostics = result.problems.map {
                    if(it.cause.isPresent)
                        Logger.error("Found exception in errors:", it.cause.get())
                    Diagnostic(it.location.asRange, it.verboseMessage, DiagnosticSeverity.Error, "jmlparser")
                }
                server.client?.publishDiagnostics(
                    PublishDiagnosticsParams(path.toUri().toString(), diagnostics)
                )
            }
        }
    }

    private fun getSourcePath(cu: CompilationUnit): Path? =
        cu.storage.map { it.sourceRoot }.orElse(null)


    fun addSourceFolder(folder: Path) {
        sourceFolders.add(folder)
        typeSolver.add(JavaParserTypeSolver(folder, config))
    }

    fun getDiagnostics(path: Path): MutableList<Diagnostic> {
        val result = getResult(path)

        if (result.isSuccessful) return arrayListOf()

        val diagnostics = result.problems.map {
            Diagnostic(it.location.asRange, it.verboseMessage, DiagnosticSeverity.Error, "jmlparser")
        }
        return diagnostics.toMutableList()
    }

    private fun getResult(path: Path): ParseResult<CompilationUnit> {
        synchronized(path2AstWait) {
            if (path in path2AstWait) {
                path2AstWait[path]!!.get()
            }
        }
        synchronized(path2Ast) {
            if (path in path2Ast) {
                return path2Ast[path]!!
            }
        }
        val future = parse(path)
        return future.get()
    }
}

class JmlTextDocumentService(private val server: JmlLanguageServer) : TextDocumentService {
    val repo = AstRepository(server, server.executorService)

    override fun didOpen(params: DidOpenTextDocumentParams) {
        Logger.info("didOpen: {}", params)
        Logger.info(params.textDocument.languageId)
        if (params.textDocument.languageId != "text/java")
            return
        val path = URI(params.textDocument.uri).toPath()
        repo.parse(path)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        Logger.info("didChange: {}", params)
        val path = URI(params.textDocument.uri).toPath()
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        Logger.info("didClose: {}", params)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        Logger.info("didSave: {}", params)
        val path = URI(params.textDocument.uri).toPath()
    }

    override fun completion(position: CompletionParams?): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
        return super.completion(position)
    }

    override fun resolveCompletionItem(unresolved: CompletionItem?): CompletableFuture<CompletionItem> {
        return super.resolveCompletionItem(unresolved)
    }

    override fun hover(params: HoverParams?): CompletableFuture<Hover> {
        return super.hover(params)
    }

    override fun signatureHelp(params: SignatureHelpParams?): CompletableFuture<SignatureHelp> {
        return super.signatureHelp(params)
    }

    override fun declaration(params: DeclarationParams?): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        return super.declaration(params)
    }

    override fun definition(params: DefinitionParams?): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        return super.definition(params)
    }

    override fun typeDefinition(params: TypeDefinitionParams?): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        return super.typeDefinition(params)
    }

    override fun implementation(params: ImplementationParams?): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        return super.implementation(params)
    }

    override fun references(params: ReferenceParams?): CompletableFuture<MutableList<out Location>> {
        return super.references(params)
    }

    override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<MutableList<out DocumentHighlight>> {
        Logger.info("documentHighlight: {}", params)
        return CompletableFuture.completedFuture(arrayListOf())
    }

    override fun documentSymbol(params: DocumentSymbolParams?): CompletableFuture<MutableList<Either<SymbolInformation, DocumentSymbol>>> {
        return super.documentSymbol(params)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<MutableList<Either<Command, CodeAction>>> {
        Logger.info("codeAction: {}", params)
        return CompletableFuture.completedFuture(arrayListOf())
    }

    override fun resolveCodeAction(unresolved: CodeAction?): CompletableFuture<CodeAction> {
        return super.resolveCodeAction(unresolved)
    }

    override fun codeLens(params: CodeLensParams): CompletableFuture<MutableList<out CodeLens>> {
        Logger.info("codeLens: {}", params)
        return CompletableFuture.completedFuture(mutableListOf())
    }

    override fun resolveCodeLens(unresolved: CodeLens): CompletableFuture<CodeLens> {
        Logger.info("codeLens: {}", unresolved)
        return CompletableFuture.completedFuture(CodeLens())
    }

    override fun formatting(params: DocumentFormattingParams?): CompletableFuture<MutableList<out TextEdit>> {
        return super.formatting(params)
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams?): CompletableFuture<MutableList<out TextEdit>> {
        return super.rangeFormatting(params)
    }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams?): CompletableFuture<MutableList<out TextEdit>> {
        return super.onTypeFormatting(params)
    }

    override fun rename(params: RenameParams?): CompletableFuture<WorkspaceEdit> {
        return super.rename(params)
    }

    override fun linkedEditingRange(params: LinkedEditingRangeParams?): CompletableFuture<LinkedEditingRanges> {
        return super.linkedEditingRange(params)
    }

    override fun willSave(params: WillSaveTextDocumentParams?) {
        super.willSave(params)
    }

    override fun willSaveWaitUntil(params: WillSaveTextDocumentParams?): CompletableFuture<MutableList<TextEdit>> {
        return super.willSaveWaitUntil(params)
    }

    override fun documentLink(params: DocumentLinkParams?): CompletableFuture<MutableList<DocumentLink>> {
        return super.documentLink(params)
    }

    override fun documentLinkResolve(params: DocumentLink?): CompletableFuture<DocumentLink> {
        return super.documentLinkResolve(params)
    }

    override fun documentColor(params: DocumentColorParams?): CompletableFuture<MutableList<ColorInformation>> {
        return super.documentColor(params)
    }

    override fun colorPresentation(params: ColorPresentationParams?): CompletableFuture<MutableList<ColorPresentation>> {
        return super.colorPresentation(params)
    }

    override fun foldingRange(params: FoldingRangeRequestParams?): CompletableFuture<MutableList<FoldingRange>> {
        return super.foldingRange(params)
    }

    override fun prepareRename(params: PrepareRenameParams?): CompletableFuture<Either<Range, PrepareRenameResult>> {
        return super.prepareRename(params)
    }

    override fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams?): CompletableFuture<MutableList<TypeHierarchyItem>> {
        return super.prepareTypeHierarchy(params)
    }

    override fun typeHierarchySupertypes(params: TypeHierarchySupertypesParams?): CompletableFuture<MutableList<TypeHierarchyItem>> {
        return super.typeHierarchySupertypes(params)
    }

    override fun typeHierarchySubtypes(params: TypeHierarchySubtypesParams?): CompletableFuture<MutableList<TypeHierarchyItem>> {
        return super.typeHierarchySubtypes(params)
    }

    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams?): CompletableFuture<MutableList<CallHierarchyItem>> {
        return super.prepareCallHierarchy(params)
    }

    override fun callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams?): CompletableFuture<MutableList<CallHierarchyIncomingCall>> {
        return super.callHierarchyIncomingCalls(params)
    }

    override fun callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams?): CompletableFuture<MutableList<CallHierarchyOutgoingCall>> {
        return super.callHierarchyOutgoingCalls(params)
    }

    override fun selectionRange(params: SelectionRangeParams?): CompletableFuture<MutableList<SelectionRange>> {
        return super.selectionRange(params)
    }

    override fun semanticTokensFull(params: SemanticTokensParams?): CompletableFuture<SemanticTokens> {
        return super.semanticTokensFull(params)
    }

    override fun semanticTokensFullDelta(params: SemanticTokensDeltaParams?): CompletableFuture<Either<SemanticTokens, SemanticTokensDelta>> {
        return super.semanticTokensFullDelta(params)
    }

    override fun semanticTokensRange(params: SemanticTokensRangeParams?): CompletableFuture<SemanticTokens> {
        return super.semanticTokensRange(params)
    }

    override fun moniker(params: MonikerParams?): CompletableFuture<MutableList<Moniker>> {
        return super.moniker(params)
    }

    override fun inlayHint(params: InlayHintParams?): CompletableFuture<MutableList<InlayHint>> {
        return super.inlayHint(params)
    }

    override fun resolveInlayHint(unresolved: InlayHint?): CompletableFuture<InlayHint> {
        return super.resolveInlayHint(unresolved)
    }

    override fun inlineValue(params: InlineValueParams?): CompletableFuture<MutableList<InlineValue>> {
        return super.inlineValue(params)
    }

    override fun diagnostic(params: DocumentDiagnosticParams): CompletableFuture<DocumentDiagnosticReport> {
        Logger.info("diagnostic: {}", params)
        val path = URI(params.textDocument.uri).toPath()

        return CompletableFutures.computeAsync {
            val errors = repo.getDiagnostics(path)
            Logger.info("Found errors: {}", errors)
            DocumentDiagnosticReport(RelatedFullDocumentDiagnosticReport(errors))
        }
    }
}
