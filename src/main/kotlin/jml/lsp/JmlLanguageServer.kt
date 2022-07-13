package jml.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class JmlLanguageServer : LanguageServer, LanguageClientAware {
    private val jmlWorkspaceService = JmlWorkspaceService()
    internal var client: LanguageClient? = null
    var capabilities: ClientCapabilities? = null
    var workspaceFolders: List<WorkspaceFolder>? = null

    internal val executorService: ExecutorService by lazy { Executors.newCachedThreadPool() }
    private val jmlTextDocumentService by lazy { JmlTextDocumentService(this) }

    override fun initialize(params: InitializeParams?): CompletableFuture<InitializeResult> {
        if (params != null) {
            workspaceFolders = params.workspaceFolders
            capabilities = params.capabilities
        }

        val capabilities = ServerCapabilities()
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
        capabilities.diagnosticProvider = DiagnosticRegistrationOptions(true, false)
        capabilities.setCodeActionProvider(true)
        capabilities.setColorProvider(false)
        capabilities.setDocumentHighlightProvider(true)
        capabilities.setDeclarationProvider(true)
        capabilities.codeLensProvider = CodeLensOptions(true)
        //capabilities.completionProvider = CompletionOptions(true, null)
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }

    override fun shutdown(): CompletableFuture<Any> {
        executorService.shutdownNow()
        return CompletableFuture.completedFuture("finish")
    }

    override fun exit() {
        System.exit(0)
    }

    override fun getTextDocumentService(): TextDocumentService = jmlTextDocumentService

    override fun getWorkspaceService(): WorkspaceService = jmlWorkspaceService

    override fun connect(client: LanguageClient?) {
        this.client = client
    }

}
