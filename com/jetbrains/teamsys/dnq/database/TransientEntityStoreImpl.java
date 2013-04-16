package com.jetbrains.teamsys.dnq.database;

import jetbrains.exodus.core.dataStructures.hash.HashSet;
import jetbrains.exodus.core.dataStructures.hash.LinkedHashSet;
import jetbrains.exodus.core.execution.locks.Latch;
import jetbrains.exodus.database.*;
import jetbrains.teamsys.dnq.runtime.queries.QueryEngine;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Vadim.Gurov
 */
public class TransientEntityStoreImpl implements TransientEntityStore, InitializingBean {

    private static final Log log = LogFactory.getLog(TransientEntityStoreImpl.class);

    private EntityStore persistentStore;
    private QueryEngine queryEngine;
    private ModelMetaData modelMetaData;
    private final Set<TransientStoreSession> sessions = new HashSet<TransientStoreSession>();
    private final ThreadLocal<TransientStoreSession> currentSession = new ThreadLocal<TransientStoreSession>();
    private final Set<TransientStoreSessionListener> listeners = new LinkedHashSet<TransientStoreSessionListener>();

    private boolean open = true;
    private String blobsStorePath;
    private File blobsStore;
    private int flushRetryOnLockConflict = 100;
    private final Latch enumContainersLock = Latch.create();
    private final Set<EnumContainer> initedContainers = new HashSet<EnumContainer>(10);
    private final Map<String, Entity> enumCache = new ConcurrentHashMap<String, Entity>();
    private final Map<String, BasePersistentClassImpl> persistentClassInstanceCache = new ConcurrentHashMap<String, BasePersistentClassImpl>();

    public TransientEntityStoreImpl() {
        if (log.isTraceEnabled()) {
            log.trace("TransientEntityStoreImpl constructor called.");
        }
    }

    public EntityStore getPersistentStore() {
        return persistentStore;
    }

    public QueryEngine getQueryEngine() {
        return queryEngine;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public void setBlobsStorePath(@NotNull String blobsStorePath) {
        this.blobsStorePath = blobsStorePath;
    }

    File getBlobsStore() {
        return blobsStore;
    }

    public int getFlushRetryOnLockConflict() {
        return flushRetryOnLockConflict;
    }

    public void setFlushRetryOnLockConflict(int flushRetryOnLockConflict) {
        this.flushRetryOnLockConflict = flushRetryOnLockConflict;
    }

    /**
     * Service locator {@link jetbrains.springframework.configuration.runtime.ServiceLocator} is responsible to set persistent entity store
     *
     * @param persistentStore persistent entity store.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public void setPersistentStore(EntityStore persistentStore) {
        this.persistentStore = persistentStore;
    }

    /**
     * Service locator {@link jetbrains.springframework.configuration.runtime.ServiceLocator} is responsible to set query engine
     *
     * @param persistentStore persistent entity store.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public void setQueryEngine(QueryEngine queryEngine) {
        this.queryEngine = queryEngine;
    }

    @NotNull
    public String getName() {
        return "transient store";
    }

    @NotNull
    public String getLocation() {
        throw new UnsupportedOperationException("Not supported by transient store.");
    }

    @NotNull
    @Override
    public StoreTransaction beginTransaction() {
        throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public StoreTransaction getCurrentTransaction() {
        throw new UnsupportedOperationException();
    }

    public TransientStoreSession beginSession() {
        assertOpen();

        if (log.isDebugEnabled()) {
            log.debug("Begin new session");
        }

        TransientStoreSession currentSession = this.currentSession.get();
        if (currentSession != null) {
            log.debug("Return session already associated with the current thread " + currentSession);
            return currentSession;
        }

        return registerStoreSession(new TransientSessionImpl(this));
    }

    public void resumeSession(TransientStoreSession session) {
        assertOpen();

        if (session != null) {
            TransientStoreSession current = currentSession.get();
            if (current != null) {
                if (current != session) {
                    throw new IllegalStateException("Another open transient session already associated with current thread.");
                }
            }

            currentSession.set(session);
        }
    }

    public void setModelMetaData(final ModelMetaData modelMetaData) {
        this.modelMetaData = modelMetaData;
    }

    @Nullable
    public ModelMetaData getModelMetaData() {
        return modelMetaData;
    }

    /**
     * It's guaranteed that current thread session is Open, if exists
     *
     * @return current thread session
     */
    @Nullable
    public TransientStoreSession getThreadSession() {
        return currentSession.get();
    }

    public void close() {
        log.debug("Close transient store.");
        open = false;

        if (sessions.size() != 0) {
            log.warn("There're " + sessions.size() + " open transient sessions.");
        }
    }

    public boolean entityTypeExists(@NotNull final String entityTypeName) {
        try {
            return ((PersistentEntityStore) persistentStore).getEntityTypeId(entityTypeName, false) >= 0;
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    public void renameEntityTypeRefactoring(@NotNull final String oldEntityTypeName, @NotNull final String newEntityTypeName) {
        final TransientSessionImpl s = (TransientSessionImpl) getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        s.addChangeAndRun(new TransientSessionImpl.MyRunnable() {
            @Override
            public boolean run() {
                ((PersistentEntityStore) s.getPersistentTransaction().getStore()).renameEntityType(oldEntityTypeName, newEntityTypeName);
                return true;
            }
        });
    }

    public void deleteEntityTypeRefactoring(@NotNull final String entityTypeName) {
        final TransientSessionImpl s = (TransientSessionImpl) getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        s.addChangeAndRun(new TransientSessionImpl.MyRunnable() {
            @Override
            public boolean run() {
                ((PersistentEntityStoreImpl) s.getPersistentTransaction().getStore()).deleteEntityType(entityTypeName);
                return true;
            }
        });
    }

    public void deleteEntityRefactoring(@NotNull Entity entity) {
        final TransientSessionImpl s = (TransientSessionImpl) getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        final Entity persistentEntity = (entity instanceof TransientEntity) ? ((TransientEntity) entity).getPersistentEntity() : entity;
        if (entity instanceof TransientEntity) {
            s.deleteEntity((TransientEntity) entity);
        } else {
            s.addChangeAndRun(new TransientSessionImpl.MyRunnable() {
                public boolean run() {
                    persistentEntity.delete();
                    return true;
                }
            });
        }
    }

    public void deleteLinksRefactoring(@NotNull final Entity entity, @NotNull final String linkName) {
        final TransientSessionImpl s = (TransientSessionImpl) getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        final Entity persistentEntity = (entity instanceof TransientEntity) ? ((TransientEntity) entity).getPersistentEntity() : entity;
        s.addChangeAndRun(new TransientSessionImpl.MyRunnable() {
            public boolean run() {
                persistentEntity.deleteLinks(linkName);
                return true;
            }
        });
    }

    public void deleteLinkRefactoring(@NotNull final Entity entity, @NotNull final String linkName, @NotNull final Entity link) {
        final TransientSessionImpl s = (TransientSessionImpl) getThreadSession();

        if (s == null) {
            throw new IllegalStateException("No current thread session.");
        }

        final Entity persistentEntity = (entity instanceof TransientEntity) ? ((TransientEntity) entity).getPersistentEntity() : entity;
        final Entity persistentLink = (link instanceof TransientEntity) ? ((TransientEntity) link).getPersistentEntity() : link;

        s.addChangeAndRun(new TransientSessionImpl.MyRunnable() {
            public boolean run() {
                persistentEntity.deleteLink(linkName, persistentLink);
                return true;
            }
        });
    }

    private TransientStoreSession registerStoreSession(TransientStoreSession s) {
        synchronized (sessions) {
            if (sessions.contains(s)) {
                throw new IllegalArgumentException("Session with already registered.");
            }

            sessions.add(s);
        }

        currentSession.set(s);

        return s;
    }

    void unregisterStoreSession(TransientStoreSession s) {
        synchronized (sessions) {
            if (!sessions.remove(s)) {
                throw new IllegalArgumentException("Transient session with wasn't previously registered.");
            }
        }

        currentSession.remove();
    }

    @Nullable
    public TransientStoreSession suspendThreadSession() {
        assertOpen();

        final TransientStoreSession current = getThreadSession();
        if (current != null) {
            currentSession.remove();
        }

        return current;
    }

    public void addListener(@NotNull TransientStoreSessionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull TransientStoreSessionListener listener) {
        listeners.remove(listener);
    }

    void forAllListeners(@NotNull ListenerVisitor v) {
        for (TransientStoreSessionListener l : listeners) {
            v.visit(l);
        }
    }

    public void afterPropertiesSet() throws Exception {
        if (blobsStorePath == null) {
            blobsStorePath = System.getProperty("java.io.tmpdir");
        }

        blobsStore = new File(blobsStorePath);

        if (!blobsStore.exists() && !blobsStore.mkdirs()) {
            throw new IllegalArgumentException("Can't create not existing directory [" + blobsStorePath + "]");
        }

        if (!blobsStore.isDirectory()) {
            throw new IllegalArgumentException("Path [" + blobsStorePath + "] should be directory.");
        }

        if (!blobsStore.canWrite() || !blobsStore.canRead()) {
            throw new IllegalArgumentException("Application must have write and read access to [" + blobsStorePath + "]");
        }

        if (log.isDebugEnabled()) {
            log.debug("Transient store will use the following path for storing blobs [" + blobsStore.getCanonicalPath() + "]");
        }
    }

    public int sessionsCount() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    public void dumpSessions(StringBuilder sb) {
        synchronized (sessions) {
            for (TransientStoreSession s : sessions) {
                sb.append("\n").append(s.toString());
            }
        }
    }

    public boolean isEnumContainerInited(EnumContainer container) {
        return initedContainers.contains(container);
    }

    public void enumContainerInited(EnumContainer container) {
        initedContainers.add(container);
    }

    public void enumContainerLock() throws InterruptedException {
        enumContainersLock.acquire();
    }

    public void enumContainerUnLock() {
        enumContainersLock.release();
    }

    public Entity getCachedEnumValue(@NotNull final String className, @NotNull final String propName) {
        return enumCache.get(getEnumKey(className, propName));
    }

    public void setCachedEnumValue(@NotNull final String className,
                                   @NotNull final String propName, @NotNull final Entity entity) {
        enumCache.put(getEnumKey(className, propName), entity);
    }

    public BasePersistentClassImpl getCachedPersistentClassInstance(@NotNull final String entityType) {
        return persistentClassInstanceCache.get(entityType);
    }

    public void setCachedPersistentClassInstance(@NotNull final String entityType, @NotNull final BasePersistentClassImpl clazz) {
        persistentClassInstanceCache.put(entityType, clazz);
    }

    private void assertOpen() {
        if (!open) throw new IllegalStateException("Transient store is closed.");
    }

    public static String getEnumKey(@NotNull final String className, @NotNull final String propName) {
        final StringBuilder builder = new StringBuilder(24);
        builder.append(propName);
        builder.append('@');
        builder.append(className);
        return builder.toString();
    }

    interface ListenerVisitor {
        void visit(TransientStoreSessionListener listener);
    }

}
