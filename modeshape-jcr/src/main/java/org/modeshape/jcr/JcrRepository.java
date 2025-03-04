/*
 * ModeShape (http://www.modeshape.org)
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
package org.modeshape.jcr;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.AccessControlContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import javax.jcr.AccessDeniedException;
import javax.jcr.Credentials;
import javax.jcr.LoginException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.naming.NoInitialContextException;
import javax.naming.OperationNotSupportedException;
import javax.security.auth.login.LoginContext;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.manager.CacheContainer;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.infinispan.schematic.Schematic;
import org.infinispan.schematic.SchematicDb;
import org.infinispan.schematic.document.Array;
import org.infinispan.schematic.document.Changes;
import org.infinispan.schematic.document.Editor;
import org.infinispan.schematic.document.Path;
import org.infinispan.schematic.internal.document.Paths;
import org.jgroups.Channel;
import org.modeshape.common.annotation.Immutable;
import org.modeshape.common.collection.Problems;
import org.modeshape.common.collection.SimpleProblems;
import org.modeshape.common.i18n.I18n;
import org.modeshape.common.logging.Logger;
import org.modeshape.common.util.NamedThreadFactory;
import org.modeshape.jcr.ModeShapeEngine.State;
import org.modeshape.jcr.RepositoryConfiguration.AnonymousSecurity;
import org.modeshape.jcr.RepositoryConfiguration.BinaryStorage;
import org.modeshape.jcr.RepositoryConfiguration.Component;
import org.modeshape.jcr.RepositoryConfiguration.DocumentOptimization;
import org.modeshape.jcr.RepositoryConfiguration.FieldName;
import org.modeshape.jcr.RepositoryConfiguration.GarbageCollection;
import org.modeshape.jcr.RepositoryConfiguration.JaasSecurity;
import org.modeshape.jcr.RepositoryConfiguration.Security;
import org.modeshape.jcr.RepositoryConfiguration.TransactionMode;
import org.modeshape.jcr.api.AnonymousCredentials;
import org.modeshape.jcr.api.Repository;
import org.modeshape.jcr.api.RepositoryManager;
import org.modeshape.jcr.api.RestoreOptions;
import org.modeshape.jcr.api.Workspace;
import org.modeshape.jcr.api.monitor.ValueMetric;
import org.modeshape.jcr.api.query.Query;
import org.modeshape.jcr.api.value.DateTime;
import org.modeshape.jcr.bus.ChangeBus;
import org.modeshape.jcr.bus.ClusteredChangeBus;
import org.modeshape.jcr.bus.RepositoryChangeBus;
import org.modeshape.jcr.cache.NodeCache;
import org.modeshape.jcr.cache.NodeKey;
import org.modeshape.jcr.cache.RepositoryCache;
import org.modeshape.jcr.cache.RepositoryEnvironment;
import org.modeshape.jcr.cache.SessionCache;
import org.modeshape.jcr.cache.WorkspaceNotFoundException;
import org.modeshape.jcr.cache.document.DocumentStore;
import org.modeshape.jcr.cache.document.LocalDocumentStore;
import org.modeshape.jcr.cache.document.TransactionalWorkspaceCaches;
import org.modeshape.jcr.clustering.ClusteringService;
import org.modeshape.jcr.federation.FederatedDocumentStore;
import org.modeshape.jcr.journal.ChangeJournal;
import org.modeshape.jcr.journal.ClusteredJournal;
import org.modeshape.jcr.journal.LocalJournal;
import org.modeshape.jcr.mimetype.MimeTypeDetector;
import org.modeshape.jcr.mimetype.MimeTypeDetectors;
import org.modeshape.jcr.query.parse.FullTextSearchParser;
import org.modeshape.jcr.query.parse.JcrQomQueryParser;
import org.modeshape.jcr.query.parse.JcrSql2QueryParser;
import org.modeshape.jcr.query.parse.JcrSqlQueryParser;
import org.modeshape.jcr.query.parse.QueryParsers;
import org.modeshape.jcr.query.xpath.XPathQueryParser;
import org.modeshape.jcr.security.AnonymousProvider;
import org.modeshape.jcr.security.AuthenticationProvider;
import org.modeshape.jcr.security.AuthenticationProviders;
import org.modeshape.jcr.security.EnvironmentAuthenticationProvider;
import org.modeshape.jcr.security.JaasProvider;
import org.modeshape.jcr.security.SecurityContext;
import org.modeshape.jcr.spi.index.IndexManager;
import org.modeshape.jcr.txn.NoClientTransactions;
import org.modeshape.jcr.txn.SynchronizedTransactions;
import org.modeshape.jcr.txn.Transactions;
import org.modeshape.jcr.value.DateTimeFactory;
import org.modeshape.jcr.value.NamespaceRegistry;
import org.modeshape.jcr.value.ValueFactories;
import org.modeshape.jcr.value.binary.BinaryStore;
import org.modeshape.jcr.value.binary.infinispan.InfinispanBinaryStore;
import org.modeshape.jmx.RepositoryStatisticsBean;

/**
 *
 */
public class JcrRepository implements org.modeshape.jcr.api.Repository {

    /**
     * The set of supported query language string constants.
     *
     * @see javax.jcr.query.QueryManager#getSupportedQueryLanguages()
     * @see javax.jcr.query.QueryManager#createQuery(String, String)
     */
    public static final class QueryLanguage {
        /**
         * The standard JCR 1.0 XPath query language.
         */
        @SuppressWarnings( "deprecation" )
        public static final String XPATH = Query.XPATH;

        /**
         * The SQL dialect that is based upon an enhanced version of the JCR-SQL query language defined by the JCR 1.0.1
         * specification.
         */
        @SuppressWarnings( "deprecation" )
        public static final String JCR_SQL = Query.SQL;

        /**
         * The SQL dialect that is based upon an enhanced version of the JCR-SQL2 query language defined by the JCR 2.0
         * specification.
         */
        public static final String JCR_SQL2 = Query.JCR_SQL2;

        /**
         * The enhanced Query Object Model language defined by the JCR 2.0 specification.
         */
        public static final String JCR_JQOM = Query.JCR_JQOM;
        /**
         * The full-text search language defined as part of the abstract query model, in Section 6.7.19 of the JCR 2.0
         * specification.
         */
        public static final String SEARCH = Query.FULL_TEXT_SEARCH;
    }

    protected static final Set<String> MISSING_JAAS_POLICIES = new CopyOnWriteArraySet<String>();

    private static final boolean AUTO_START_REPO_UPON_LOGIN = true;

    private static final String INTERNAL_WORKER_USERNAME = "<modeshape-worker>";

    protected final Logger logger;
    private final AtomicReference<RepositoryConfiguration> config = new AtomicReference<RepositoryConfiguration>();
    private final AtomicReference<String> repositoryName = new AtomicReference<String>();
    private final Map<String, Object> descriptors;
    private final AtomicReference<RunningState> runningState = new AtomicReference<RunningState>();
    private final AtomicReference<State> state = new AtomicReference<State>(State.NOT_RUNNING);
    private final Lock stateLock = new ReentrantLock();
    private final AtomicBoolean allowAutoStartDuringLogin = new AtomicBoolean(AUTO_START_REPO_UPON_LOGIN);
    private Problems configurationProblems = null;

    /**
     * Create a Repository instance given the {@link RepositoryConfiguration configuration}.
     *
     * @param configuration the repository configuration; may not be null
     * @throws ConfigurationException if there is a problem with the configuration
     */
    protected JcrRepository( RepositoryConfiguration configuration ) throws ConfigurationException {
        ModeShape.getName(); // force log message right up front
        this.config.set(configuration);
        RepositoryConfiguration config = this.config.get();

        // Validate the configuration to make sure there are no errors ...
        Problems results = configuration.validate();
        setConfigurationProblems(results);
        if (results.hasErrors()) {
            String msg = JcrI18n.errorsInRepositoryConfiguration.text(this.repositoryName, results.errorCount(),
                                                                      results.toString());
            throw new ConfigurationException(results, msg);
        }

        this.repositoryName.set(config.getName());
        this.logger = Logger.getLogger(getClass());
        this.logger.debug("Activating '{0}' repository", this.repositoryName);

        // Set up the descriptors ...
        this.descriptors = new HashMap<String, Object>();
        initializeDescriptors();
    }

    void setConfigurationProblems( Problems configurationProblems ) {
        this.configurationProblems = configurationProblems;
    }

    RepositoryConfiguration repositoryConfiguration() {
        return config.get();
    }

    /**
     * Get the state of this JCR repository instance.
     *
     * @return the state; never null
     */
    public State getState() {
        return state.get();
    }

    /**
     * Get the name of this JCR repository instance.
     *
     * @return the name; never null
     */
    @Override
    public String getName() {
        return repositoryName.get();
    }

    @Override
    public int getActiveSessionsCount() {
        RunningState state = runningState.get();
        return state == null ? 0 : state.activeSessionCount();
    }

    /**
     * Get the component that can be used to obtain statistics for this repository.
     * <p>
     * Note that this provides un-checked access to the statistics, unlike {@link RepositoryManager#getRepositoryMonitor()} in the
     * public API which only exposes the statistics if the session's user has administrative privileges.
     * </p>
     *
     * @return the statistics component; never null
     * @throws IllegalStateException if the repository is not {@link #getState() running}
     * @see Workspace#getRepositoryManager()
     * @see RepositoryManager#getRepositoryMonitor()
     */
    public RepositoryStatistics getRepositoryStatistics() {
        return statistics();
    }

    /**
     * Starts this repository instance (if not already started) and returns all the possible startup problems & warnings which did
     * not prevent the repository from starting up.
     * <p>
     * The are 2 general categories of issues that can be logged as problems:
     * <ul>
     * <li>configuration warnings - any warnings raised by the structure of the repository configuration file</li>
     * <li>startup warnings/error - any warnings/errors raised by various repository components which didn't prevent them from
     * starting up, but could mean they are only partly intialized.</li>
     * </ul>
     * </p>
     *
     * @return a {@link Problems} instance which may contains errors and warnings raised by various components; may be empty if
     *         nothing unusual happened during start but never {@code null}
     * @throws FileNotFoundException if the Infinispan configuration file is specified but could not be found
     * @throws IOException if there is a problem with the specified Infinispan configuration file
     * @throws Exception if there is a problem with underlying resource setup
     */
    public Problems getStartupProblems() throws Exception {
        doStart();
        SimpleProblems result = new SimpleProblems();
        result.addAll(this.configurationProblems);
        result.addAll(runningState().problems());
        return result;
    }

    /**
     * Start this repository instance.
     *
     * @throws FileNotFoundException if the Infinispan configuration file is specified but could not be found
     * @throws IOException if there is a problem with the specified Infinispan configuration file
     * @throws Exception if there is a problem with underlying resource setup
     */
    void start() throws Exception {
        doStart();
    }

    /**
     * Terminate all active sessions.
     *
     * @return a future representing the asynchronous session termination process.
     */
    Future<Boolean> shutdown() {
        // Create a simple executor that will do the backgrounding for us ...
        final ExecutorService executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("modeshape-repository-stop"));
        try {
            // Submit a runnable to terminate all sessions ...
            Future<Boolean> future = executor.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    return doShutdown();
                }
            });
            return future;
        } finally {
            // Now shutdown the executor and return the future ...
            executor.shutdown();
        }
    }

    /**
     * Apply the supplied changes to this repository's configuration, and if running change the services to reflect the updated
     * configuration. Note that this method assumes the proposed changes have already been validated; see
     * {@link RepositoryConfiguration#validate(Changes)}.
     *
     * @param changes the changes for the configuration
     * @throws FileNotFoundException if the Infinispan configuration file is changed but could not be found
     * @throws IOException if there is a problem with the specified Infinispan configuration file
     * @throws Exception if there is a problem with underlying resources
     * @see ModeShapeEngine#update(String, Changes)
     */
    void apply( Changes changes ) throws Exception {
        try {
            stateLock.lock();
            logger.debug("Applying changes to '{0}' repository configuration: {1} --> {2}", repositoryName, changes, config);
            // Get the configuration and apply the same changes ...
            final RepositoryConfiguration oldConfiguration = this.config.get();
            Editor copy = oldConfiguration.edit();
            ConfigurationChange configChanges = new ConfigurationChange();
            copy.apply(changes, configChanges);

            // Always update the configuration ...
            RunningState oldState = this.runningState.get();
            this.config.set(new RepositoryConfiguration(copy.unwrap(), copy.getString(FieldName.NAME),
                                                        oldConfiguration.environment()));
            if (oldState != null) {
                assert state.get() == State.RUNNING;
                // Repository is running, so create a new running state ...
                this.runningState.set(new RunningState(oldState, configChanges));

                // Handle a few special cases that the running state doesn't really handle itself ...
                if (!configChanges.storageChanged && configChanges.predefinedWorkspacesChanged) refreshWorkspaces();
                if (configChanges.nameChanged) repositoryNameChanged();
            }
            logger.debug("Applied changes to '{0}' repository configuration: {1} --> {2}", repositoryName, changes, config);
        } finally {
            stateLock.unlock();
        }
    }

    protected final RunningState doStart() throws Exception {
        try {
            stateLock.lock();
            if (this.state.get() == State.RESTORING) {
                throw new IllegalStateException(JcrI18n.repositoryIsBeingRestoredAndCannotBeStarted.text(getName()));
            }
            RunningState state = this.runningState.get();
            if (state == null) {
                // start the repository by creating the running state ...
                this.state.set(State.STARTING);
                state = new RunningState();
                this.runningState.compareAndSet(null, state);
                state.completeInitialization();
                this.state.set(State.RUNNING);
                state.postInitialize();
            }
            return state;
        } catch (Exception e) {
            // we should set the state to NOT_RUNNING regardless of the error/exception that occurs
            this.state.set(State.NOT_RUNNING);
            throw e;
        } finally {
            stateLock.unlock();
        }
    }

    protected final boolean doShutdown() {
        if (this.state.get() == State.NOT_RUNNING) return true;
        try {
            stateLock.lock();
            RunningState running = this.runningState.get();
            if (running != null) {
                // Prevent future 'login(...)' calls from restarting the repository ...
                this.allowAutoStartDuringLogin.set(false);

                this.state.set(State.STOPPING);

                // Terminate each of the still-open sessions ...
                running.terminateSessions();

                // Now shutdown the running state ...
                running.shutdown();

                // Null out the running state ...
                this.runningState.set(null);
            }
            this.state.set(State.NOT_RUNNING);
        } finally {
            stateLock.unlock();
        }
        return true;
    }

    protected final IndexManager getIndexManager() {
        return runningState().queryManager().getIndexManager();
    }

    protected final DocumentStore documentStore() {
        return runningState().documentStore();
    }

    protected final String repositoryName() {
        return repositoryName.get();
    }

    protected final RepositoryCache repositoryCache() {
        return runningState().repositoryCache();
    }

    protected final RepositoryStatistics statistics() {
        return runningState().statistics();
    }

    protected final RepositoryNodeTypeManager nodeTypeManager() {
        return runningState().nodeTypeManager();
    }

    protected final RepositoryQueryManager queryManager() {
        return runningState().queryManager();
    }

    protected final RepositoryLockManager lockManager() {
        return runningState().lockManager();
    }

    protected final NamespaceRegistry persistentRegistry() {
        return runningState().persistentRegistry();
    }

    protected final String systemWorkspaceName() {
        return runningState().systemWorkspaceName();
    }

    protected final String systemWorkspaceKey() {
        return runningState().systemWorkspaceKey();
    }

    protected final ChangeBus changeBus() {
        return runningState().changeBus();
    }

    protected final String repositoryKey() {
        return runningState().repositoryKey();
    }

    protected final JcrRepository.RunningState runningState() {
        RunningState running = runningState.get();
        if (running == null) {
            throw new IllegalStateException(JcrI18n.repositoryIsNotRunningOrHasBeenShutDown.text(repositoryName()));
        }
        return running;
    }

    protected final boolean hasWorkspace( String workspaceName ) {
        return repositoryCache().getWorkspaceNames().contains(workspaceName);
    }

    protected final NodeCache workspaceCache( String workspaceName ) {
        return repositoryCache().getWorkspaceCache(workspaceName);
    }

    final SessionCache createSystemSession( ExecutionContext context,
                                            boolean readOnly ) {
        return repositoryCache().createSession(context, systemWorkspaceName(), readOnly);
    }

    protected final TransactionManager transactionManager() {
        return runningState().txnManager();
    }

    protected final void prepareToRestore() throws RepositoryException {
        logger.debug("Preparing to restore '{0}' repository; setting state to RESTORING", getName());
        if (getState() == State.RESTORING) {
            throw new RepositoryException(JcrI18n.repositoryIsCurrentlyBeingRestored.text(getName()));
        }
        state.set(State.RESTORING);
    }

    protected final String journalId() {
        return runningState().journalId();
    }

    protected final ChangeJournal journal() {
        return runningState().journal();
    }

    protected final boolean versioningUsed() {
        return runningState().repositoryCache().versioningUsed();
    }

    protected final boolean lockingUsed() {
        return runningState().repositoryCache().lockingUsed();
    }

    protected final void completeRestore(RestoreOptions options) throws ExecutionException, Exception {
        if (getState() == State.RESTORING) {
            logger.debug("Performing custom system initialization on '{0}' after content has been restored", getName());
            runningState().completeRestore();
            logger.debug("Shutting down '{0}' after content has been restored", getName());
            doShutdown();
            logger.debug("Starting '{0}' after content has been restored", getName());
            start();
            logger.debug("Started '{0}' after content has been restored; beginning indexing of content", getName());
            if (options.reindexContentOnFinish()) {
                // Reindex all content ...
                queryManager().cleanAndReindex(false);
                logger.debug("Completed reindexing all content in '{0}' after restore.", getName());
            }
        }
    }

    /**
     * Get the immutable configuration for this repository.
     *
     * @return the configuration; never null
     */
    public RepositoryConfiguration getConfiguration() {
        return this.config.get();
    }

    @Override
    public String getDescriptor( String key ) {
        if (key == null) return null;
        if (!isSingleValueDescriptor(key)) return null;

        JcrValue value = (JcrValue)descriptors.get(key);
        try {
            return value.getString();
        } catch (RepositoryException re) {
            throw new IllegalStateException(re);
        }
    }

    @Override
    public JcrValue getDescriptorValue( String key ) {
        if (key == null) return null;
        if (!isSingleValueDescriptor(key)) return null;
        return (JcrValue)descriptors.get(key);
    }

    @Override
    public JcrValue[] getDescriptorValues( String key ) {
        Object value = descriptors.get(key);
        if (value instanceof JcrValue[]) {
            // Make a defensive copy of the array; the elements are immutable ...
            JcrValue[] values = (JcrValue[])value;
            JcrValue[] newValues = new JcrValue[values.length];
            System.arraycopy(values, 0, newValues, 0, values.length);
            return newValues;
        }
        if (value instanceof JcrValue) {
            return new JcrValue[] {(JcrValue)value};
        }
        return null;
    }

    @Override
    public boolean isSingleValueDescriptor( String key ) {
        if (key == null) return true;
        return descriptors.get(key) instanceof JcrValue;
    }

    @Override
    public boolean isStandardDescriptor( String key ) {
        return STANDARD_DESCRIPTORS.contains(key);
    }

    @Override
    public String[] getDescriptorKeys() {
        return descriptors.keySet().toArray(new String[descriptors.size()]);
    }

    @Override
    public synchronized JcrSession login() throws RepositoryException {
        return login(null, null);
    }

    @Override
    public synchronized JcrSession login( Credentials credentials ) throws RepositoryException {
        return login(credentials, null);
    }

    @Override
    public synchronized JcrSession login( String workspaceName ) throws RepositoryException {
        return login(null, workspaceName);
    }

    /**
     * @throws IllegalArgumentException if <code>credentials</code> is not <code>null</code> but:
     *         <ul>
     *         <li>provides neither a <code>getLoginContext()</code> nor a <code>getAccessControlContext()</code> method and is
     *         not an instance of {@code SimpleCredentials}.</li>
     *         <li>provides a <code>getLoginContext()</code> method that doesn't return a {@link LoginContext}.
     *         <li>provides a <code>getLoginContext()</code> method that returns a <code>
     *         null</code> {@link LoginContext}.
     *         <li>does not provide a <code>getLoginContext()</code> method, but provides a <code>getAccessControlContext()</code>
     *         method that doesn't return an {@link AccessControlContext}.
     *         <li>does not provide a <code>getLoginContext()</code> method, but provides a <code>getAccessControlContext()</code>
     *         method that returns a <code>null</code> {@link AccessControlContext}.
     *         </ul>
     * @see javax.jcr.Repository#login(javax.jcr.Credentials, java.lang.String)
     */
    @Override
    public synchronized JcrSession login( final Credentials credentials,
                                          String workspaceName ) throws RepositoryException {
        final String repoName = this.repositoryName();

        // Get the running state ...
        RunningState running = this.runningState.get();
        if (running == null) {
            if (this.allowAutoStartDuringLogin.get()) {
                // Try starting ...
                try {
                    running = doStart();
                } catch (Throwable t) {
                    throw new RepositoryException(JcrI18n.errorStartingRepository.text(repoName, t.getMessage()), t);
                }
                if (running == null) {
                    throw new RepositoryException(JcrI18n.repositoryIsNotRunningOrHasBeenShutDown.text(repoName));
                }
            } else {
                throw new RepositoryException(JcrI18n.repositoryIsNotRunningOrHasBeenShutDown.text(repoName));
            }
        } else {
            if (this.state.get() == State.RESTORING) {
                throw new RepositoryException(JcrI18n.repositoryIsBeingRestoredAndCannotBeStarted.text(getName()));
            }
        }

        workspaceName = validateWorkspaceName(running, workspaceName);
        final AuthenticationProviders authenticators = running.authenticators();
        final Credentials anonCredentials = running.anonymousCredentials();
        final Map<String, Object> attributes = new HashMap<String, Object>();

        // Try to authenticate with the provider(s) ...
        ExecutionContext context = running.context();
        ExecutionContext sessionContext = authenticators.authenticate(credentials, repoName, workspaceName, context, attributes);

        if (sessionContext == null && credentials != null && anonCredentials != null) {
            // Failed non-anonymous authentication, so try anonymous authentication ...
            if (logger.isDebugEnabled()) logger.debug(JcrI18n.usingAnonymousUser.text());
            attributes.clear();
            sessionContext = authenticators.authenticate(anonCredentials, repoName, workspaceName, context, attributes);
        }

        if (sessionContext == null) {
            // Failed authentication ...
            throw new javax.jcr.LoginException(JcrI18n.loginFailed.text(repoName, workspaceName));
        }

        // We have successfully authenticated ...
        try {
            // Look for whether this context is read-only ...
            SecurityContext securityContext = sessionContext.getSecurityContext();
            boolean writable = JcrSession.hasRole(securityContext, ModeShapeRoles.READWRITE, repoName, workspaceName)
                               || JcrSession.hasRole(securityContext, ModeShapeRoles.ADMIN, repoName, workspaceName);
            JcrSession session = null;
            if (running.useXaSessions()) {
                session = new JcrXaSession(this, workspaceName, sessionContext, attributes, !writable);
            } else {
                session = new JcrSession(this, workspaceName, sessionContext, attributes, !writable);
            }

            // Need to make sure that the user has access to this session
            session.checkWorkspacePermission(workspaceName, ModeShapePermissions.READ);
            running.addSession(session, false);
            return session;
        } catch (AccessDeniedException ace) {
            throw new LoginException(JcrI18n.loginFailed.text(repoName, workspaceName), ace);
        } catch (WorkspaceNotFoundException e) {
            throw new NoSuchWorkspaceException(e.getMessage(), e);
        }
    }

    private String validateWorkspaceName( RunningState runningState,
                                          String workspaceName ) throws RepositoryException {
        if (workspaceName == null) {
            return runningState.defaultWorkspaceName();
        }
        if (runningState.systemWorkspaceName().equals(workspaceName)) {
            throw new NoSuchWorkspaceException(JcrI18n.workspaceNameIsInvalid.text(repositoryName(), workspaceName));
        }
        return workspaceName;
    }

    protected static class ConfigurationChange implements org.infinispan.schematic.document.Editor.Observer {

        private final Path SECURITY_PATH = Paths.path(FieldName.SECURITY);
        private final Path SEQUENCING_PATH = Paths.path(FieldName.SEQUENCING);
        private final Path EXTRACTORS_PATH = Paths.path(FieldName.TEXT_EXTRACTION, FieldName.EXTRACTORS);
        private final Path INDEXES_PATH = Paths.path(FieldName.INDEXES);
        private final Path INDEX_PROVIDERS_PATH = Paths.path(FieldName.INDEX_PROVIDERS);
        private final Path STORAGE_PATH = Paths.path(FieldName.STORAGE);
        private final Path BINARY_STORAGE_PATH = Paths.path(FieldName.STORAGE, FieldName.BINARY_STORAGE);
        private final Path WORKSPACES_PATH = Paths.path(FieldName.WORKSPACES);
        private final Path PREDEFINED_PATH = Paths.path(FieldName.WORKSPACES, FieldName.PREDEFINED);
        private final Path JNDI_PATH = Paths.path(FieldName.JNDI_NAME);
        private final Path TRANSACTION_MODE_PATH = Paths.path(FieldName.TRANSACTION_MODE);
        private final Path MINIMUM_BINARY_SIZE_IN_BYTES_PATH = Paths.path(FieldName.STORAGE, FieldName.BINARY_STORAGE,
                                                                          FieldName.MINIMUM_BINARY_SIZE_IN_BYTES);
        private final Path NAME_PATH = Paths.path(FieldName.NAME);
        private final Path MONITORING_PATH = Paths.path(FieldName.MONITORING);

        private final Path[] IGNORE_PATHS = new Path[] {STORAGE_PATH, BINARY_STORAGE_PATH};

        protected boolean securityChanged = false;
        protected boolean sequencingChanged = false;
        protected boolean extractorsChanged = false;
        protected boolean storageChanged = false;
        protected boolean binaryStorageChanged = false;
        protected boolean indexProvidersChanged = false;
        protected boolean indexesChanged = false;
        protected boolean workspacesChanged = false;
        protected boolean predefinedWorkspacesChanged = false;
        protected boolean jndiChanged = false;
        protected boolean transactionMode = false;
        protected boolean largeValueChanged = false;
        protected boolean nameChanged = false;
        protected boolean monitoringChanged = false;

        @Override
        public void setArrayValue( Path path,
                                   Array.Entry entry ) {
            checkForChanges(path);
        }

        @Override
        public void addArrayValue( Path path,
                                   Array.Entry entry ) {
            checkForChanges(path);
        }

        @Override
        public void removeArrayValue( Path path,
                                      Array.Entry entry ) {
            checkForChanges(path);
        }

        @Override
        public void clear( Path path ) {
            checkForChanges(path);
        }

        @Override
        public void put( Path parentPath,
                         String field,
                         Object newValue ) {
            checkForChanges(parentPath.with(field));
        }

        @Override
        public void remove( Path path,
                            String field ) {
            checkForChanges(path.with(field));
        }

        private void checkForChanges( Path path ) {
            for (Path ignorePath : IGNORE_PATHS) {
                if (path.equals(ignorePath)) return;
            }

            if (!largeValueChanged && path.equals(MINIMUM_BINARY_SIZE_IN_BYTES_PATH)) largeValueChanged = true;
            else if (!binaryStorageChanged && path.startsWith(BINARY_STORAGE_PATH)) binaryStorageChanged = true;
            else if (!storageChanged && path.startsWith(STORAGE_PATH)) storageChanged = true;
            if (!sequencingChanged && path.startsWith(SEQUENCING_PATH)) sequencingChanged = true;
            if (!extractorsChanged && path.startsWith(EXTRACTORS_PATH)) extractorsChanged = true;
            if (!securityChanged && path.startsWith(SECURITY_PATH)) securityChanged = true;
            if (!workspacesChanged && path.startsWith(WORKSPACES_PATH) && !path.startsWith(PREDEFINED_PATH)) workspacesChanged = true;
            if (!predefinedWorkspacesChanged && path.startsWith(PREDEFINED_PATH)) predefinedWorkspacesChanged = true;
            if (!indexesChanged && path.startsWith(INDEXES_PATH)) indexesChanged = true;
            if (!indexProvidersChanged && path.startsWith(INDEX_PROVIDERS_PATH)) indexProvidersChanged = true;
            if (!jndiChanged && path.equals(JNDI_PATH)) jndiChanged = true;
            if (!transactionMode && path.equals(TRANSACTION_MODE_PATH)) transactionMode = true;
            if (!nameChanged && path.equals(NAME_PATH)) nameChanged = true;
            if (!monitoringChanged && path.equals(MONITORING_PATH)) monitoringChanged = true;
        }
    }

    @SuppressWarnings( "deprecation" )
    private void initializeDescriptors() {
        ValueFactories factories = new ExecutionContext().getValueFactories();

        descriptors.put(Repository.LEVEL_1_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.LEVEL_2_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.OPTION_LOCKING_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.OPTION_OBSERVATION_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.OPTION_QUERY_SQL_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.OPTION_TRANSACTIONS_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.OPTION_VERSIONING_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.QUERY_XPATH_DOC_ORDER, valueFor(factories, false)); // see MODE-613
        descriptors.put(Repository.QUERY_XPATH_POS_INDEX, valueFor(factories, false)); // no support doc order searching in xpath

        descriptors.put(Repository.WRITE_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.IDENTIFIER_STABILITY, valueFor(factories, Repository.IDENTIFIER_STABILITY_INDEFINITE_DURATION));
        descriptors.put(Repository.OPTION_XML_IMPORT_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.OPTION_XML_EXPORT_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.OPTION_UNFILED_CONTENT_SUPPORTED, valueFor(factories, false));
        descriptors.put(Repository.OPTION_SIMPLE_VERSIONING_SUPPORTED, valueFor(factories, false));
        descriptors.put(Repository.OPTION_ACTIVITIES_SUPPORTED, valueFor(factories, false));
        descriptors.put(Repository.OPTION_BASELINES_SUPPORTED, valueFor(factories, false));
        descriptors.put(Repository.OPTION_ACCESS_CONTROL_SUPPORTED, valueFor(factories, true));
        JcrValue journalingValue = valueFor(factories, repositoryConfiguration().getJournaling().isEnabled());
        descriptors.put(Repository.OPTION_JOURNALED_OBSERVATION_SUPPORTED, journalingValue);
        descriptors.put(Repository.OPTION_RETENTION_SUPPORTED, valueFor(factories, false));
        descriptors.put(Repository.OPTION_LIFECYCLE_SUPPORTED, valueFor(factories, false));
        descriptors.put(Repository.OPTION_NODE_AND_PROPERTY_WITH_SAME_NAME_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.OPTION_UPDATE_PRIMARY_NODE_TYPE_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.OPTION_UPDATE_MIXIN_NODE_TYPES_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.OPTION_SHAREABLE_NODES_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.OPTION_NODE_TYPE_MANAGEMENT_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.NODE_TYPE_MANAGEMENT_INHERITANCE,
                        valueFor(factories, Repository.NODE_TYPE_MANAGEMENT_INHERITANCE_MULTIPLE));
        descriptors.put(Repository.NODE_TYPE_MANAGEMENT_PRIMARY_ITEM_NAME_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.NODE_TYPE_MANAGEMENT_ORDERABLE_CHILD_NODES_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.NODE_TYPE_MANAGEMENT_RESIDUAL_DEFINITIONS_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.NODE_TYPE_MANAGEMENT_AUTOCREATED_DEFINITIONS_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.NODE_TYPE_MANAGEMENT_SAME_NAME_SIBLINGS_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.NODE_TYPE_MANAGEMENT_PROPERTY_TYPES, valueFor(factories, true));
        descriptors.put(Repository.NODE_TYPE_MANAGEMENT_OVERRIDES_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.NODE_TYPE_MANAGEMENT_MULTIVALUED_PROPERTIES_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.NODE_TYPE_MANAGEMENT_MULTIPLE_BINARY_PROPERTIES_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.NODE_TYPE_MANAGEMENT_VALUE_CONSTRAINTS_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.NODE_TYPE_MANAGEMENT_UPDATE_IN_USE_SUPORTED, valueFor(factories, true));
        descriptors.put(Repository.QUERY_LANGUAGES,
                        new JcrValue[] {valueFor(factories, Query.XPATH), valueFor(factories, Query.JCR_SQL2),
                            valueFor(factories, Query.SQL), valueFor(factories, Query.JCR_JQOM)});
        descriptors.put(Repository.QUERY_STORED_QUERIES_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.QUERY_FULL_TEXT_SEARCH_SUPPORTED, valueFor(factories, true));
        descriptors.put(Repository.QUERY_JOINS, valueFor(factories, Repository.QUERY_JOINS_INNER_OUTER));
        descriptors.put(Repository.SPEC_NAME_DESC, valueFor(factories, JcrI18n.SPEC_NAME_DESC.text()));
        descriptors.put(Repository.SPEC_VERSION_DESC, valueFor(factories, "2.0"));

        descriptors.put(Repository.REP_NAME_DESC, valueFor(factories, ModeShape.getName()));
        descriptors.put(Repository.REP_VENDOR_DESC, valueFor(factories, ModeShape.getVendor()));
        descriptors.put(Repository.REP_VENDOR_URL_DESC, valueFor(factories, ModeShape.getUrl()));
        descriptors.put(Repository.REP_VERSION_DESC, valueFor(factories, ModeShape.getVersion()));
        descriptors.put(Repository.OPTION_WORKSPACE_MANAGEMENT_SUPPORTED, valueFor(factories, true));

        descriptors.put(Repository.REPOSITORY_NAME, valueFor(factories, repositoryName()));
    }

    private static JcrValue valueFor( ValueFactories valueFactories,
                                      int type,
                                      Object value ) {
        return new JcrValue(valueFactories, type, value);
    }

    private static JcrValue valueFor( ValueFactories valueFactories,
                                      String value ) {
        return valueFor(valueFactories, PropertyType.STRING, value);
    }

    private static JcrValue valueFor( ValueFactories valueFactories,
                                      boolean value ) {
        return valueFor(valueFactories, PropertyType.BOOLEAN, value);
    }

    protected void refreshWorkspaces() {
        RunningState running = runningState();
        if (running != null) {
            Set<String> workspaceNames = running.repositoryCache().getWorkspaceNames();
            ValueFactories factories = running.context().getValueFactories();
            JcrValue[] values = new JcrValue[workspaceNames.size()];
            int i = 0;
            for (String workspaceName : workspaceNames) {
                values[i++] = valueFor(factories, workspaceName);
            }
            descriptors.put(Repository.REPOSITORY_WORKSPACES, values);
        }
    }

    private void repositoryNameChanged() {
        descriptors.put(Repository.REPOSITORY_NAME, repositoryName());
    }

    Collection<Cache<?, ?>> caches() {
        RunningState running = runningState.get();
        if (running == null) return Collections.emptyList();

        List<Cache<?, ?>> caches = new ArrayList<Cache<?, ?>>();
        LocalDocumentStore localDocumentStore = running.documentStore().localStore();
        caches.add(localDocumentStore.localCache());
        // Add the binary store's cache, if there is one ...
        BinaryStore store = running.binaryStore();
        if (store instanceof InfinispanBinaryStore) {
            InfinispanBinaryStore ispnStore = (InfinispanBinaryStore)store;
            caches.addAll(ispnStore.getCaches());
        }

        return caches;
    }

    @Immutable
    protected class RunningState {

        private final RepositoryConfiguration config;
        private final DocumentStore documentStore;
        private final AuthenticationProviders authenticators;
        private final Credentials anonymousCredentialsIfSuppliedCredentialsFail;
        private final String defaultWorkspaceName;
        private final String systemWorkspaceName;
        private final String systemWorkspaceKey;
        private final RepositoryNodeTypeManager nodeTypes;
        private final RepositoryLockManager lockManager;
        private final TransactionManager txnMgr;
        private final Transactions transactions;
        private final String jndiName;
        private final SystemNamespaceRegistry persistentRegistry;
        private final ExecutionContext context;
        private final ExecutionContext internalWorkerContext;
        private final ReadWriteLock activeSessionLock = new ReentrantReadWriteLock();
        private final WeakHashMap<JcrSession, Object> activeSessions = new WeakHashMap<JcrSession, Object>();
        private final WeakHashMap<JcrSession, Object> internalSessions = new WeakHashMap<JcrSession, Object>();
        private final RepositoryStatistics statistics;
        private final RepositoryStatisticsBean mbean;
        private final BinaryStore binaryStore;
        private final ScheduledExecutorService statsRollupService;
        private final Sequencers sequencers;
        private final QueryParsers queryParsers;
        private final RepositoryQueryManager repositoryQueryManager;
        private final ExecutorService indexingExecutor;
        private final TextExtractors extractors;
        private final ChangeBus changeBus;
        private final ExecutorService changeDispatchingQueue;
        private final boolean useXaSessions;
        private final MimeTypeDetectors mimeTypeDetector;
        private final BackupService backupService;
        private final InitialContentImporter initialContentImporter;
        private final SystemContentInitializer systemContentInitializer;
        private final NodeTypesImporter nodeTypesImporter;
        private final Connectors connectors;
        private final List<ScheduledFuture<?>> backgroundProcesses = new ArrayList<ScheduledFuture<?>>();
        private final Problems problems;
        private final ChangeJournal journal;
        private final ClusteringService clusteringService;

        private Transaction existingUserTransaction;
        private RepositoryCache cache;

        protected RunningState() throws Exception {
            this(null, null);
        }

        @SuppressWarnings( "deprecation" )
        protected RunningState( JcrRepository.RunningState other,
                                JcrRepository.ConfigurationChange change ) throws Exception {
            this.config = repositoryConfiguration();
            this.systemContentInitializer = new SystemContentInitializer();
            if (other == null) {
                logger.debug("Starting '{0}' repository with configuration: \n{1}", repositoryName(), this.config);
                this.problems = new SimpleProblems();
            } else {
                logger.debug("Updating '{0}' repository with configuration: \n{1}", repositoryName(), this.config);
                this.problems = other.problems;
            }
            ExecutionContext tempContext = new ExecutionContext();

            // Set up monitoring (doing this early in the process so it is available to other components to use) ...
            if (other != null && !change.monitoringChanged) {
                this.statistics = other.statistics;
                this.statsRollupService = other.statsRollupService;
                this.mbean = other.mbean;
            } else {
                this.statistics = other != null ? other.statistics : new RepositoryStatistics(tempContext);
                if (this.config.getMonitoring().enabled()) {
                    // Start the Cron service, with a minimum of a single thread ...
                    this.statsRollupService = tempContext.getScheduledThreadPool("modeshape-stats");
                    this.statistics.start(this.statsRollupService);
                    this.mbean = new RepositoryStatisticsBean(statistics, getName());
                    this.mbean.start();
                } else {
                    this.statsRollupService = null;
                    this.mbean = null;
                }
            }

            this.systemWorkspaceName = RepositoryConfiguration.SYSTEM_WORKSPACE_NAME;
            this.systemWorkspaceKey = NodeKey.keyForWorkspaceName(this.systemWorkspaceName);

            if (other != null && !change.workspacesChanged) {
                this.defaultWorkspaceName = other.defaultWorkspaceName;
            } else {
                // Set up some of the defaults ...
                this.defaultWorkspaceName = config.getDefaultWorkspaceName();
            }

            try {
                if (other != null) {
                    if (change.storageChanged) {
                        // Can't change where we're storing the content while we're running, so take effect upon next startup
                        warn(JcrI18n.storageRelatedConfigurationChangesWillTakeEffectAfterShutdown, getName());
                    }
                    if (change.binaryStorageChanged) {
                        // Can't change where we're storing the content while we're running, so take effect upon next startup
                        warn(JcrI18n.storageRelatedConfigurationChangesWillTakeEffectAfterShutdown, getName());
                    }
                    // reuse the existing storage-related components ...
                    this.cache = other.cache;
                    this.context = other.context;
                    this.connectors = other.connectors;
                    this.documentStore = other.documentStore;
                    this.txnMgr = documentStore.transactionManager();

                    this.transactions = createTransactions(this.cache.getName(), config.getTransactionMode(), this.txnMgr);

                    suspendExistingUserTransaction();

                    if (change.largeValueChanged) {
                        // We can update the value used in the repository cache dynamically ...
                        BinaryStorage binaryStorage = config.getBinaryStorage();
                        this.cache.setLargeStringLength(binaryStorage.getMinimumBinarySizeInBytes());
                        this.context.getBinaryStore().setMinimumBinarySizeInBytes(binaryStorage.getMinimumBinarySizeInBytes());
                    }
                    if (change.predefinedWorkspacesChanged) {
                        // Make sure that all the predefined workspaces are available ...
                        for (String workspaceName : config.getPredefinedWorkspaceNames()) {
                            this.cache.createWorkspace(workspaceName);
                        }
                    }
                    this.mimeTypeDetector = new MimeTypeDetectors(other.config.environment(), this.problems);
                    this.binaryStore = other.binaryStore;
                    this.changeBus = other.changeBus;
                    this.internalWorkerContext = other.internalWorkerContext;
                    this.nodeTypes = other.nodeTypes.with(this, true, true);
                    this.lockManager = other.lockManager.with(this, other.config.getGarbageCollection());
                    // We have to register new components that depend on this instance ...
                    this.changeBus.unregister(other.nodeTypes);
                    this.changeBus.unregister(other.lockManager);
                    this.changeBus.register(this.nodeTypes);
                    this.changeBus.register(this.lockManager);
                    this.persistentRegistry = other.persistentRegistry;
                    this.changeDispatchingQueue = other.changeDispatchingQueue;
                    this.clusteringService = other.clusteringService;
                    this.journal = other.journal;
                } else {
                    // find the Schematic database and Infinispan Cache ...
                    CacheContainer container = config.getContentCacheContainer();
                    String cacheName = config.getCacheName();
                    List<Component> connectorComponents = config.getFederation().getConnectors(this.problems);
                    Map<String, List<RepositoryConfiguration.ProjectionConfiguration>> preconfiguredProjectionsByWorkspace = config.getFederation()
                                                                                                                                   .getProjectionsByWorkspace();
                    Set<String> extSources = config.getFederation().getExternalSources();
                    this.connectors = new Connectors(this, connectorComponents, extSources, preconfiguredProjectionsByWorkspace);                    
                    logger.debug("Loading cache '{0}' from cache container {1}", cacheName, container);
                    SchematicDb database = Schematic.get(container, cacheName);

                    Channel cacheChannel = checkClustering(database);
                    this.clusteringService = cacheChannel != null ? ClusteringService.startForked(cacheChannel) : null;

                    this.documentStore = connectors.hasConnectors() ? new FederatedDocumentStore(connectors, database) : new LocalDocumentStore(
                                                                                                                                                database);
                    this.txnMgr = this.documentStore.transactionManager();
                    this.transactions = createTransactions(cacheName, config.getTransactionMode(), this.txnMgr);

                    suspendExistingUserTransaction();

                    // Set up the binary store ...
                    BinaryStorage binaryStorageConfig = config.getBinaryStorage();
                    binaryStore = binaryStorageConfig.getBinaryStore();
                    binaryStore.start();
                    tempContext = tempContext.with(binaryStore);

                    // Now create the registry implementation and the execution context that uses it ...
                    this.persistentRegistry = new SystemNamespaceRegistry(this);
                    this.mimeTypeDetector = new MimeTypeDetectors(this.config.environment(), this.problems);
                    this.context = tempContext.with(persistentRegistry);
                    this.persistentRegistry.setContext(this.context);
                    this.internalWorkerContext = this.context.with(new InternalSecurityContext(INTERNAL_WORKER_USERNAME));

                    // Create clustering service and event bus
                    this.changeDispatchingQueue = this.context().getCachedTreadPool("modeshape-event-dispatcher", 
                                                                                    Integer.MAX_VALUE);
                    ChangeBus localBus = new RepositoryChangeBus(name(), changeDispatchingQueue, statistics(), RepositoryChangeBus.DEFAULT_RING_BUFFER_SIZE);
                    this.changeBus = clusteringService != null ? new ClusteredChangeBus(localBus, clusteringService) : localBus;
                    this.changeBus.start();

                    // Set up the event journal
                    RepositoryConfiguration.Journaling journaling = config.getJournaling();
                    if (journaling.isEnabled()) {
                        boolean asyncWritesEnabled = journaling.asyncWritesEnabled();
                        LocalJournal localJournal = new LocalJournal(journaling.location(), asyncWritesEnabled,
                                                                     journaling.maxDaysToKeepRecords());
                        this.journal = clusteringService != null ? new ClusteredJournal(localJournal, clusteringService) : localJournal;
                        this.journal.start();
                        if (asyncWritesEnabled) {
                            // Register the journal as a normal asynchronous listener ...
                            this.changeBus.register(journal);
                        } else {
                            // Register the journal
                            this.changeBus.registerInThread(journal);
                        }
                    } else {
                        this.journal = null;
                    }

                    // Set up the repository cache ...
                    final RepositoryEnvironment repositoryEnvironment = new JcrRepositoryEnvironment(this.transactions, journalId());
                    CacheContainer workspaceCacheContainer = this.config.getWorkspaceContentCacheContainer();
                    this.cache = new RepositoryCache(context, documentStore, clusteringService, config, systemContentInitializer,
                                                     repositoryEnvironment, changeBus, workspaceCacheContainer, Upgrades.STANDARD_UPGRADES);

                    // Set up the node type manager ...
                    this.nodeTypes = new RepositoryNodeTypeManager(this, true, true);
                    this.changeBus.register(this.nodeTypes);

                    // Set up the lock manager ...
                    this.lockManager = new RepositoryLockManager(this, config.getGarbageCollection());
                    this.changeBus.register(this.lockManager);

                    // Set up the monitoring listener ...
                    this.changeBus.register(this.statistics);

                    // Refresh several of the components information from the repository cache ...
                    this.persistentRegistry.refreshFromSystem();
                    this.lockManager.refreshFromSystem();
                    if (!this.nodeTypes.refreshFromSystem()) {
                        try {
                            // Read in the built-in node types ...
                            CndImporter importer = new CndImporter(context);
                            importer.importBuiltIns(this.problems);
                            this.nodeTypes.registerNodeTypes(importer.getNodeTypeDefinitions(), false, true, true);
                        } catch (RepositoryException re) {
                            throw new IllegalStateException("Could not load node type definition files", re);
                        } catch (IOException ioe) {
                            throw new IllegalStateException("Could not access node type definition files", ioe);
                        }
                    }
                    // Add the built-ins, ensuring we overwrite any badly-initialized values ...
                    this.persistentRegistry.register(JcrNamespaceRegistry.STANDARD_BUILT_IN_NAMESPACES_BY_PREFIX);

                    // Record the number of workspaces that are available/predefined ...
                    this.statistics.set(ValueMetric.WORKSPACE_COUNT, cache.getWorkspaceNames().size());
                }

                this.useXaSessions = this.transactions instanceof SynchronizedTransactions;

                if (other != null && !change.securityChanged) {
                    this.authenticators = other.authenticators;
                    this.anonymousCredentialsIfSuppliedCredentialsFail = other.anonymousCredentialsIfSuppliedCredentialsFail;
                } else {
                    // Set up the security ...
                    AtomicBoolean useAnonymouOnFailedLogins = new AtomicBoolean();
                    this.authenticators = createAuthenticationProviders(useAnonymouOnFailedLogins);
                    this.anonymousCredentialsIfSuppliedCredentialsFail = useAnonymouOnFailedLogins.get() ? new AnonymousCredentials() : null;
                }

                if (other != null && !change.extractorsChanged) {
                    this.extractors = new TextExtractors(this, other.config.getTextExtraction());
                } else {
                    this.extractors = new TextExtractors(this, config.getTextExtraction());
                }
                this.binaryStore.setMimeTypeDetector(this.mimeTypeDetector);
                this.binaryStore.setTextExtractors(this.extractors);

                if (other != null && !change.sequencingChanged) {
                    this.sequencers = other.sequencers.with(this);
                    if (!sequencers.isEmpty()) this.changeBus.register(this.sequencers);
                    this.changeBus.unregister(other.sequencers);
                } else {
                    this.sequencers = new Sequencers(this, config, cache.getWorkspaceNames());
                }

                this.indexingExecutor = this.context.getThreadPool("modeshape-reindexing");
                this.queryParsers = new QueryParsers(new JcrSql2QueryParser(), new XPathQueryParser(),
                                                     new FullTextSearchParser(), new JcrSqlQueryParser(), new JcrQomQueryParser());
                this.repositoryQueryManager = new RepositoryQueryManager(this, indexingExecutor, config);
                this.changeBus.register(this.repositoryQueryManager);

                // Check that we have parsers for all the required languages ...
                assert this.queryParsers.getParserFor(Query.XPATH) != null;
                assert this.queryParsers.getParserFor(Query.SQL) != null;
                assert this.queryParsers.getParserFor(Query.JCR_SQL2) != null;
                assert this.queryParsers.getParserFor(Query.JCR_JQOM) != null;
                assert this.queryParsers.getParserFor(QueryLanguage.SEARCH) != null;

                if (other != null && !change.jndiChanged) {
                    // The repository is already registered (or not registered)
                    this.jndiName = other.jndiName;
                } else {
                    // The JNDI location has changed, so register the new one ...
                    this.jndiName = config.getJndiName();
                    bindIntoJndi();

                    // And unregister the old name ...
                    if (other != null) {
                        other.unbindFromJndi();
                    }
                }

                // Set up the backup service and executor ...
                this.backupService = new BackupService(this);

                // Set up the initial content importer
                this.initialContentImporter = new InitialContentImporter(config.getInitialContent(), this);

                // Set up the node types importer (but don't import them yet)
                this.nodeTypesImporter = new NodeTypesImporter(config.getNodeTypes(), this);

            } catch (Throwable t) {
                // remove the document that was written as part of the initialization procedure
                if (cache != null) {
                    cache.rollbackRepositoryInfo();
                }
                // resume any user transaction that may have been suspended earlier
                resumeExistingUserTransaction();
                throw (t instanceof Exception) ? (Exception)t : new RuntimeException(t);
            }
        }

        protected Channel checkClustering( SchematicDb database ) {
            Channel cacheChannel = null;
            if (database.getCache() instanceof AdvancedCache) {
                RpcManager rpcManager = ((AdvancedCache<?, ?>)database.getCache()).getRpcManager();
                if (rpcManager != null && rpcManager.getTransport() instanceof JGroupsTransport) {
                    cacheChannel = ((JGroupsTransport)rpcManager.getTransport()).getChannel();
                }
            }
            if (logger.isDebugEnabled()) {
                if (cacheChannel != null) {
                    logger.debug("ModeShape detected active Infinispan cluster '{0}' and will be started in clustered mode",
                                 cacheChannel.getClusterName());
                } else {
                    logger.debug("ModeShape could not detect an active Infinispan cluster and will be started in non-clustered mode");
                }
            }
            return cacheChannel;
        }

        protected Transactions createTransactions( String cacheName,
                                                   TransactionMode mode,
                                                   TransactionManager txnMgr ) {
            if (txnMgr == null) {
                throw new ConfigurationException(JcrI18n.repositoryCannotBeStartedWithoutTransactionalSupport.text(getName(),
                                                                                                                   cacheName));
            }

            switch (mode) {
                case NONE:
                    return new NoClientTransactions(txnMgr);
                case AUTO:
                    break;
            }
            return new SynchronizedTransactions(txnMgr, documentStore.localStore().localCache());
        }

        /**
         * Performs the steps required after the running state has been created and before a repository is considered
         * "initialized"
         *
         * @throws Exception if anything goes wrong in this phase. If it does, the transaction used for startup should be rolled
         *         back
         */
        protected final void completeInitialization() throws Exception {
            try {
                refreshWorkspaces();

                this.sequencers.initialize();

                // import the preconfigured node types before the initial content, in case the latter use custom types
                this.nodeTypesImporter.importNodeTypes();

                // Load the index definitions AFTER the node types were imported ...
                this.queryManager().getIndexManager().importIndexDefinitions();

                if (repositoryCache().isInitializingRepository()) {
                    // import initial content for each of the workspaces; this has to be done after the running state has started
                    this.cache.runOneTimeSystemInitializationOperation(new Callable<Void>() {
                        @Override
                        public Void call() throws Exception {
                            for (String workspaceName : repositoryCache().getWorkspaceNames()) {
                                initialContentImporter().importInitialContent(workspaceName);
                            }
                            return null;
                        }
                    });
                }

                // connectors must be initialized after initial content because that can have an influence on projections
                this.connectors.initialize();

                // only mark the query manager as initialed *after* all the other components have finished initializing
                // otherwise we risk getting indexing/scanning events for components which have not finished initializing (e.g. connectors)
                this.repositoryQueryManager.initialize();

                // Now record in the content that we're finished initializing the repository.
                // This will commit the startup transaction.
                repositoryCache().completeInitialization();

                // Now complete the upgrade of the repository, if required. This will be done transactionally.
                repositoryCache().completeUpgrade(new Upgrades.Context() {
                    @Override
                    public RunningState getRepository() {
                        return RunningState.this;
                    }

                    @Override
                    public Problems getProblems() {
                        return RunningState.this.problems();
                    }
                });
            } catch (Throwable t) {
                repositoryCache().rollbackRepositoryInfo();
                resumeExistingUserTransaction();
                throw t instanceof Exception ? (Exception)t : new RuntimeException(t);
            }
        }

        /**
         * Perform any initialization code that requires the repository to be in a running state. The repository has been
         * considered started up.
         *
         * @throws Exception if there is a problem during this phase.
         */
        protected final void postInitialize() throws Exception {
            try {
                // Have the query manager tell the providers to initialize the indexes. This may cause a background reindexing ...
                queryManager().reindexIfNeeded();

                // Register the background processes.
                // Do this last since we want the repository running before these are started ...
                GarbageCollection gcConfig = config.getGarbageCollection();
                String threadPoolName = gcConfig.getThreadPoolName();
                long gcInitialTimeInMillis = determineInitialDelay(gcConfig.getInitialTimeExpression());
                long gcIntervalInMillis = gcConfig.getIntervalInMillis();
             
                assert gcInitialTimeInMillis >= 0;
                ScheduledExecutorService garbageCollectionService = this.context.getScheduledThreadPool(threadPoolName);
                backgroundProcesses.add(garbageCollectionService.scheduleAtFixedRate(new LockGarbageCollectionTask(
                                                                                                                   JcrRepository.this),
                                                                                     gcInitialTimeInMillis,
                                                                                     gcIntervalInMillis,
                                                                                     TimeUnit.MILLISECONDS));
                backgroundProcesses.add(garbageCollectionService.scheduleAtFixedRate(new BinaryValueGarbageCollectionTask(
                                                                                                                          JcrRepository.this),
                                                                                     gcInitialTimeInMillis,
                                                                                     gcIntervalInMillis,
                                                                                     TimeUnit.MILLISECONDS));

                DocumentOptimization optConfig = config.getDocumentOptimization();
                if (optConfig.isEnabled()) {
                    warn(JcrI18n.enablingDocumentOptimization, name());
                    threadPoolName = optConfig.getThreadPoolName();
                    long optInitialTimeInMillis = determineInitialDelay(optConfig.getInitialTimeExpression());
                    long optIntervalInHours = optConfig.getIntervalInHours();
                    int targetCount = optConfig.getChildCountTarget();
                    int tolerance = optConfig.getChildCountTolerance();
                    assert optInitialTimeInMillis >= 0;
                    long optIntervalInMillis = TimeUnit.MILLISECONDS.convert(optIntervalInHours, TimeUnit.HOURS);
                    ScheduledExecutorService optService = this.context.getScheduledThreadPool(threadPoolName);
                    OptimizationTask optTask = new OptimizationTask(JcrRepository.this, targetCount, tolerance);
                    backgroundProcesses.add(optService.scheduleAtFixedRate(optTask, optInitialTimeInMillis, optIntervalInMillis,
                                                                           TimeUnit.MILLISECONDS));
                }

                if (journal != null) {
                    RepositoryConfiguration.Journaling journalingCfg = config.getJournaling();
                    if (journalingCfg.maxDaysToKeepRecords() > 0) {
                        threadPoolName = journalingCfg.getThreadPoolName();
                        long initialTimeInMillis = determineInitialDelay(journalingCfg.getInitialTimeExpression());
                        assert initialTimeInMillis >= 0;
                        long intervalInHours = journalingCfg.getIntervalInHours();
                        long intervalInMillis = TimeUnit.MILLISECONDS.convert(intervalInHours, TimeUnit.HOURS);
                        ScheduledExecutorService journalingGCService = this.context.getScheduledThreadPool(threadPoolName);
                        backgroundProcesses.add(journalingGCService.scheduleAtFixedRate(new JournalingGCTask(JcrRepository.this),
                                                                                        initialTimeInMillis, intervalInMillis,
                                                                                        TimeUnit.MILLISECONDS));
                    }
                }
            } finally {
                resumeExistingUserTransaction();
            }
        }

        protected final Sequencers sequencers() {
            return sequencers;
        }

        protected final boolean useXaSessions() {
            return useXaSessions;
        }

        final String name() {
            return repositoryName();
        }

        final ExecutionContext context() {
            return context;
        }

        final ExecutionContext internalWorkerContext() {
            return internalWorkerContext;
        }

        final RepositoryCache repositoryCache() {
            return cache;
        }

        final ChangeJournal journal() {
            return journal;
        }

        final String journalId() {
            return journal != null ? journal.journalId() : null;
        }

        final ClusteringService clusteringService() {
            return clusteringService;
        }

        final QueryParsers queryParsers() {
            return queryParsers;
        }

        final RepositoryQueryManager queryManager() {
            return repositoryQueryManager;
        }

        protected final DocumentStore documentStore() {
            return documentStore;
        }

        protected final BinaryStore binaryStore() {
            return binaryStore;
        }

        protected final MimeTypeDetector mimeTypeDetector() {
            return mimeTypeDetector;
        }

        protected final TextExtractors textExtractors() {
            return extractors;
        }

        protected final Environment environment() {
            return config.environment();
        }

        protected final TransactionManager txnManager() {
            TransactionManager mgr = documentStore().transactionManager();
            assert mgr != null;
            return mgr;
        }

        protected final RepositoryNodeTypeManager nodeTypeManager() {
            return nodeTypes;
        }

        protected final RepositoryLockManager lockManager() {
            return lockManager;
        }

        protected final String systemWorkspaceName() {
            return systemWorkspaceName;
        }

        protected final String systemWorkspaceKey() {
            return systemWorkspaceKey;
        }

        protected final String defaultWorkspaceName() {
            return defaultWorkspaceName;
        }

        protected final NamespaceRegistry persistentRegistry() {
            return persistentRegistry;
        }

        protected final AuthenticationProviders authenticators() {
            return authenticators;
        }

        protected final RepositoryStatistics statistics() {
            return statistics;
        }

        protected final Credentials anonymousCredentials() {
            return anonymousCredentialsIfSuppliedCredentialsFail;
        }

        protected final ChangeBus changeBus() {
            return changeBus;
        }

        final Connectors connectors() {
            return connectors;
        }

        protected final String repositoryKey() {
            return cache.getKey();
        }

        protected final BackupService backupService() {
            return backupService;
        }

        protected final void warn( I18n message,
                                   Object... params ) {
            logger.warn(message, params);
            problems.addWarning(message, params);
        }

        protected final void error( I18n message,
                                    Object... params ) {
            logger.error(message, params);
            problems.addError(message, params);
        }

        protected final void error( Throwable t,
                                    I18n message,
                                    Object... params ) {
            logger.error(t, message, params);
            problems.addError(t, message, params);
        }

        protected final Problems problems() {
            return this.problems;
        }

        final InitialContentImporter initialContentImporter() {
            return initialContentImporter;
        }

        private AuthenticationProviders createAuthenticationProviders( AtomicBoolean useAnonymouOnFailedLogins ) {
            // Prepare to create the authenticators and authorizers ...
            AuthenticationProviders authenticators = new AuthenticationProviders();
            Security securityConfig = config.getSecurity();

            // Set up the JAAS providers ...
            JaasSecurity jaasSecurity = securityConfig.getJaas();
            if (jaasSecurity != null) {
                String policyName = jaasSecurity.getPolicyName();
                if (policyName != null && policyName.trim().length() != 0) {
                    try {
                        JaasProvider jaasProvider = new JaasProvider(policyName);
                        authenticators = authenticators.with(jaasProvider);
                    } catch (java.lang.SecurityException e) {
                        if (MISSING_JAAS_POLICIES.add(policyName)) {
                            warn(JcrI18n.loginConfigNotFound, policyName, RepositoryConfiguration.FieldName.SECURITY + "/"
                                                                          + RepositoryConfiguration.FieldName.JAAS_POLICY_NAME,
                                 repositoryName());
                        }
                    } catch (javax.security.auth.login.LoginException e) {
                        if (MISSING_JAAS_POLICIES.add(policyName)) {
                            warn(JcrI18n.loginConfigNotFound, policyName, RepositoryConfiguration.FieldName.SECURITY + "/"
                                                                          + RepositoryConfiguration.FieldName.JAAS_POLICY_NAME,
                                 repositoryName());
                        }
                    }
                }
            }

            // Set up any custom AuthenticationProvider classes ...
            for (Component component : securityConfig.getCustomProviders(problems())) {
                try {
                    AuthenticationProvider provider = component.createInstance(getClass().getClassLoader());
                    authenticators = authenticators.with(provider);
                    if (provider instanceof AnonymousProvider) {
                        Object value = component.getDocument().get(FieldName.USE_ANONYMOUS_ON_FAILED_LOGINS);
                        if (Boolean.TRUE.equals(value)) {
                            useAnonymouOnFailedLogins.set(true);
                        }
                    }
                    if (provider instanceof EnvironmentAuthenticationProvider) {
                        EnvironmentAuthenticationProvider envProvider = (EnvironmentAuthenticationProvider) provider;
                        String securityDomain = component.getDocument().getString(FieldName.SECURITY_DOMAIN);
                        envProvider.setSecurityDomain(securityDomain);
                        envProvider.setEnvironment(environment());
                        envProvider.initialize();
                    }
                } catch (Throwable t) {
                    logger.error(t, JcrI18n.unableToInitializeAuthenticationProvider, component, repositoryName(), t.getMessage());
                }
            }

            // And last set up the anonymous provider ...
            AnonymousSecurity anonSecurity = securityConfig.getAnonymous();
            if (anonSecurity != null) {
                // Set up the anonymous provider (if appropriate) ...
                Set<String> anonRoles = anonSecurity.getAnonymousRoles();
                if (!anonRoles.isEmpty()) {
                    String anonUsername = anonSecurity.getAnonymousUsername();
                    AnonymousProvider anonProvider = new AnonymousProvider(anonUsername, anonRoles);
                    authenticators = authenticators.with(anonProvider);
                    logger.debug("Enabling anonymous authentication and authorization.");
                }
                if (anonSecurity.useAnonymousOnFailedLogings()) {
                    useAnonymouOnFailedLogins.set(true);
                }
            }

            return authenticators;
        }

        protected void completeRestore() {
            SessionCache systemSession = createSystemSession(context, false);
            // make sure we clear the ws cache to avoid any stale leftover data from the restoring repository
            systemSession.getWorkspace().clear();
            RestoreContentInitializer restoreContentInitializer = new RestoreContentInitializer();
            restoreContentInitializer.initialize(systemSession, systemSession.mutable(systemSession.getRootKey()));
            systemSession.save();            
        }
        
        final SessionCache createSystemSession( ExecutionContext context,
                                                boolean readOnly ) {
            return cache.createSession(context, systemWorkspaceName(), readOnly);
        }

        protected void shutdown() {
            // if reindexing was asynchronous and is still going on, we need to terminate it before we stop any of caches
            // or we do anything that affects the nodes
            this.repositoryQueryManager.stopReindexing();

            // shutdown the connectors
            this.connectors.shutdown();

            // Remove the scheduled operations ...
            for (ScheduledFuture<?> future : backgroundProcesses) {
                future.cancel(true);
            }

            // Unregister from JNDI ...
            unbindFromJndi();

            // Shutdown the sequencers ...
            sequencers().shutdown();

            // Now wait until all the internal sessions are gone ...
            if (!internalSessions.isEmpty()) {
                try {
                    int counter = 200; // this will block at most for 10 sec (200*50ms)
                    while (counter > 0 && !internalSessions.isEmpty()) {
                        Thread.sleep(50L);
                        --counter;
                    }
                } catch (InterruptedException e) {
                    // do nothing ...
                }
            }

            // Now shutdown the repository caches ...
            this.cache.startShutdown();

            // shutdown the clustering service
            if (this.clusteringService != null) {
                this.clusteringService.shutdown();
            }

            // shutdown the event bus
            if (this.changeBus != null) {
                this.changeBus.shutdown();
            }

            // shutdown the journal
            if (this.journal != null) {
                this.journal.shutdown();
            }

            // Shutdown the query engine ...
            repositoryQueryManager.shutdown();

            // Shutdown the text extractors
            if (extractors != null) {
                extractors.shutdown();
            }

            if (backupService != null) {
                backupService.shutdown();
            }

            // Shutdown the binary store ...
            this.binaryStore.shutdown();

            // Now shutdown the repository caches ...
            this.cache.completeShutdown();

            if (statistics != null) {
                statistics.stop();
            }

            if (mbean != null) {
                mbean.stop();
            }

            this.context().terminateAllPools(30, TimeUnit.SECONDS);

            // Shutdown the environment's resources.
            this.environment().shutdown();
        }

        protected void bindIntoJndi() {
            if (jndiName != null && jndiName.trim().length() != 0) {
                try {
                    InitialContext ic = new InitialContext();
                    ic.bind(jndiName, JcrRepository.this);
                } catch (NoInitialContextException e) {
                    // No JNDI here ...
                    logger.debug("No JNDI found, so not registering '{0}' repository", getName());
                } catch (OperationNotSupportedException e) {
                    warn(JcrI18n.jndiReadOnly, config.getName(), jndiName);
                } catch (NamingException e) {
                    logger.error(e, JcrI18n.unableToBindToJndi, config.getName(), jndiName, e.getMessage());
                } catch (Exception e) {
                    logger.debug(e, "Error while registering the '{0}' repository from the '{1}' name in JNDI", config.getName(),
                                 jndiName);
                }
            }
        }

        protected void unbindFromJndi() {
            if (jndiName != null && jndiName.trim().length() != 0) {
                try {
                    InitialContext ic = new InitialContext();
                    ic.unbind(jndiName);
                } catch (NoInitialContextException e) {
                    // No JNDI here ...
                    logger.debug("No JNDI found, so not registering '{0}' repository", getName());
                } catch (OperationNotSupportedException e) {
                    warn(JcrI18n.jndiReadOnly, config.getName(), jndiName);
                } catch (Exception e) {
                    logger.warn(JcrI18n.jndiReadOnly, config.getName(), jndiName);
                }
            }
        }

        void addSession( JcrSession session,
                         boolean internal ) {
            Map<JcrSession, Object> sessions = internal ? internalSessions : activeSessions;
            Lock lock = this.activeSessionLock.writeLock();
            try {
                lock.lock();
                sessions.put(session, null);
            } finally {
                lock.unlock();
            }
        }

        int activeSessionCount() {
            Lock lock = this.activeSessionLock.writeLock();
            try {
                lock.lock();
                return activeSessions.size();
            } finally {
                lock.unlock();
            }
        }

        void removeSession( JcrSession session ) {
            Lock lock = this.activeSessionLock.writeLock();
            try {
                lock.lock();
                if (activeSessions.remove(session) == null) {
                    internalSessions.remove(session);
                }
            } finally {
                lock.unlock();
            }
        }

        void terminateSessions() {
            Lock lock = this.activeSessionLock.writeLock();
            try {
                lock.lock();
                for (JcrSession session : this.activeSessions.keySet()) {
                    session.terminate(false); // don't remove from active sessions, as we're blocked and iterating on it ...
                }
                this.activeSessions.clear();
            } finally {
                lock.unlock();
            }
        }

        /**
         * @see LockGarbageCollectionTask
         */
        void cleanUpLocks() {
            if (logger.isDebugEnabled()) {
                logger.debug("Starting lock cleanup in the '{0}' repository", repositoryName());
            }

            Set<String> activeSessionIds = new HashSet<String>();
            try {
                // Get the IDs for the active sessions ...
                Lock lock = this.activeSessionLock.writeLock();
                try {
                    lock.lock();
                    Iterator<Map.Entry<JcrSession, Object>> iter = this.activeSessions.entrySet().iterator();
                    while (iter.hasNext()) {
                        JcrSession session = iter.next().getKey();
                        if (session.isLive()) activeSessionIds.add(session.sessionId());
                    }
                } finally {
                    lock.unlock();
                }

                this.lockManager().cleanupLocks(activeSessionIds);
                if (logger.isDebugEnabled()) {
                    logger.debug("Finishing lock cleanup in the '{0}' repository", repositoryName());
                }
            } catch (TimeoutException te) {
                // some locks could not be obtained in ISPN while trying to execute the job
                // just log the exception since the transaction should've rolled back and we'll retry this job anyway later on
                if (logger.isDebugEnabled()) {
                    logger.debug(te, "A timeout occurred in ISPN while attempting to clean the JCR locks for the sessions: {0}",
                                 activeSessionIds);
                }
            } catch (Throwable e) {
                logger.error(e, JcrI18n.errorDuringGarbageCollection, e.getMessage());
            }
        }

        /**
         * @see BinaryValueGarbageCollectionTask
         */
        void cleanUpBinaryValues() {
            if (logger.isDebugEnabled()) {
                logger.debug("Starting binary value cleanup in the '{0}' repository", repositoryName());
            }
            try {
                this.binaryStore.removeValuesUnusedLongerThan(RepositoryConfiguration.UNUSED_BINARY_VALUE_AGE_IN_MILLIS,
                                                              TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                logger.error(e, JcrI18n.errorDuringGarbageCollection, e.getMessage());
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Finishing binary value cleanup in the '{0}' repository", repositoryName());
            }
        }

        protected Session loginInternalSession() throws RepositoryException {
            return loginInternalSession(defaultWorkspaceName());
        }

        protected JcrSession loginInternalSession( String workspaceName ) throws RepositoryException {
            try {
                boolean readOnly = false; // assume not
                RunningState running = runningState();
                ExecutionContext sessionContext = running.internalWorkerContext();
                Map<String, Object> attributes = Collections.emptyMap();
                JcrSession session = new JcrSession(JcrRepository.this, workspaceName, sessionContext, attributes, readOnly);
                running.addSession(session, true);
                return session;
            } catch (WorkspaceNotFoundException e) {
                throw new NoSuchWorkspaceException(e.getMessage(), e);
            }
        }

        boolean suspendExistingUserTransaction() throws SystemException {
            // suspend any potential existing transaction, so that the initialization is "atomic"
            this.existingUserTransaction = this.transactions.suspend();
            return this.existingUserTransaction != null;
        }

        void resumeExistingUserTransaction() throws SystemException {
            if (transactions != null && existingUserTransaction != null) {
                transactions.resume(existingUserTransaction);
                existingUserTransaction = null;
            }
        }
    }

    protected class JcrRepositoryEnvironment implements RepositoryEnvironment {
        private final TransactionalWorkspaceCaches transactionalWorkspaceCacheFactory;
        private final Transactions transactions;
        private final String journalId;

        protected JcrRepositoryEnvironment( Transactions transactions,
                                            String journalId ) {
            this.transactions = transactions;
            this.transactionalWorkspaceCacheFactory = new TransactionalWorkspaceCaches(transactions);
            this.journalId = journalId;
        }

        @Override
        public Transactions getTransactions() {
            return transactions;
        }

        @Override
        public TransactionalWorkspaceCaches getTransactionalWorkspaceCacheFactory() {
            return transactionalWorkspaceCacheFactory;
        }

        @Override
        public String journalId() {
            return journalId;
        }

        @Override
        public NodeTypes nodeTypes() {
            if (runningState.get() == null) {
                // not initialized yet
                return null;
            }
            return runningState().nodeTypeManager().getNodeTypes();
        }
    }

    private final class InternalSecurityContext implements SecurityContext {
        private final String username;

        protected InternalSecurityContext( String username ) {
            this.username = username;
        }

        @Override
        public boolean isAnonymous() {
            return false;
        }

        @Override
        public String getUserName() {
            return username;
        }

        @Override
        public boolean hasRole( String roleName ) {
            return true;
        }

        @Override
        public void logout() {
            // do nothing
        }

    }

    /**
     * Determine the initial delay before the garbage collection process(es) should be run, based upon the supplied initial
     * expression. Note that the initial expression specifies the hours and minutes in local time, whereas this method should
     * return the delay in milliseconds after the current time.
     *
     * @param initialTimeExpression the expression of the form "<code>hh:mm</code>"; never null
     * @return the number of milliseconds after now that the process(es) should be started
     */
    protected long determineInitialDelay( String initialTimeExpression ) {
        Matcher matcher = RepositoryConfiguration.INITIAL_TIME_PATTERN.matcher(initialTimeExpression);
        if (matcher.matches()) {
            int hours = Integer.decode(matcher.group(1));
            int mins = Integer.decode(matcher.group(2));
            DateTimeFactory factory = runningState().context().getValueFactories().getDateFactory();
            DateTime now = factory.create();
            DateTime initialTime = factory.create(now.getYear(), now.getMonthOfYear(), now.getDayOfMonth(), hours, mins, 0, 0);
            long delay = initialTime.getMilliseconds() - System.currentTimeMillis();
            if (delay <= 0L) {
                initialTime = initialTime.plusDays(1);
                delay = initialTime.getMilliseconds() - System.currentTimeMillis();
            }
            if (delay < 10000L) delay += 10000L; // at least 10 second delay to let repository finish starting ...
            assert delay >= 0;
            return delay;
        }
        String msg = JcrI18n.invalidGarbageCollectionInitialTime.text(repositoryName(), initialTimeExpression);
        throw new IllegalArgumentException(msg);
    }

    /**
     * The garbage collection tasks should get cancelled before the repository is shut down, but just in case we'll use a weak
     * reference to hold onto the JcrRepository instance and we'll also check that the repository is running before we actually do
     * any work.
     */
    protected static abstract class BackgroundRepositoryTask implements Runnable {
        private WeakReference<JcrRepository> repositoryRef;

        protected BackgroundRepositoryTask( JcrRepository repository ) {
            assert repository != null;
            this.repositoryRef = new WeakReference<JcrRepository>(repository);
        }

        @Override
        public final void run() {
            JcrRepository repository = repositoryRef.get();
            if (repository != null && repository.getState() == State.RUNNING) {
                doRun(repository);
            }
        }

        /**
         * Perform the garbage collection task.
         *
         * @param repository the non-null and {@link State#RUNNING running} repository instance
         */
        protected abstract void doRun( JcrRepository repository );
    }

    protected static class BinaryValueGarbageCollectionTask extends BackgroundRepositoryTask {
        protected BinaryValueGarbageCollectionTask( JcrRepository repository ) {
            super(repository);
        }

        @Override
        protected void doRun( JcrRepository repository ) {
            repository.runningState().cleanUpBinaryValues();
        }
    }

    protected static class LockGarbageCollectionTask extends BackgroundRepositoryTask {
        protected LockGarbageCollectionTask( JcrRepository repository ) {
            super(repository);
        }

        @Override
        protected void doRun( JcrRepository repository ) {
            repository.runningState().cleanUpLocks();
        }
    }

    protected static class OptimizationTask extends BackgroundRepositoryTask {
        private final int targetCount;
        private final int tolerance;

        protected OptimizationTask( JcrRepository repository,
                                    int targetCount,
                                    int tolerance ) {
            super(repository);
            this.targetCount = targetCount;
            this.tolerance = tolerance;
        }

        @Override
        protected void doRun( JcrRepository repository ) {
            repository.runningState().repositoryCache().optimizeChildren(targetCount, tolerance);
        }
    }

    protected static class JournalingGCTask extends BackgroundRepositoryTask {
        protected JournalingGCTask( JcrRepository repository ) {
            super(repository);
        }

        @Override
        protected void doRun( JcrRepository repository ) {
            ChangeJournal journal = repository.runningState().journal();
            assert journal != null;
            journal.removeOldRecords();
        }
    }
}
