package jetbrains.teamsys.dnq.runtime.events;

/*Generated by MPS */

import jetbrains.exodus.database.TransientStoreSessionListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.util.Map;
import java.util.Queue;
import jetbrains.exodus.core.dataStructures.hash.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import jetbrains.exodus.database.TransientEntityStore;
import jetbrains.exodus.core.execution.DelegatingJobProcessor;
import jetbrains.exodus.core.execution.ThreadJobProcessor;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jetbrains.annotations.Nullable;
import java.util.Set;
import jetbrains.exodus.database.TransientEntityChange;
import org.jetbrains.annotations.NotNull;
import jetbrains.exodus.database.TransientChangesTracker;
import jetbrains.exodus.database.exceptions.DataIntegrityViolationException;
import jetbrains.exodus.entitystore.Entity;
import jetbrains.mps.baseLanguage.closures.runtime._FunctionTypes;
import jetbrains.exodus.database.TransientEntity;
import java.util.concurrent.ConcurrentLinkedQueue;
import jetbrains.exodus.core.dataStructures.hash.HashSet;
import jetbrains.exodus.database.EntityChangeType;
import jetbrains.exodus.entitystore.metadata.ModelMetaData;
import jetbrains.springframework.configuration.runtime.ServiceLocator;
import jetbrains.exodus.entitystore.metadata.EntityMetaData;
import jetbrains.exodus.core.execution.ThreadJobProcessorPool;
import jetbrains.exodus.core.execution.Job;
import jetbrains.teamsys.dnq.runtime.txn._Txn;
import jetbrains.exodus.entitystore.EntityStore;
import jetbrains.exodus.entitystore.EntityId;

public class EventsMultiplexer implements TransientStoreSessionListener {
  protected static Log log = LogFactory.getLog(EventsMultiplexer.class);

  private Map<EventsMultiplexer.FullEntityId, Queue<IEntityListener>> instanceToListeners = new HashMap<EventsMultiplexer.FullEntityId, Queue<IEntityListener>>();
  private Map<String, Queue<IEntityListener>> typeToListeners = new HashMap<String, Queue<IEntityListener>>();
  private ConcurrentHashMap<TransientEntityStore, DelegatingJobProcessor<ThreadJobProcessor>> store2processor = new ConcurrentHashMap<TransientEntityStore, DelegatingJobProcessor<ThreadJobProcessor>>();
  private ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
  private boolean open = true;

  public EventsMultiplexer() {
  }

  public void flushed(@Nullable Set<TransientEntityChange> changes) {
    // do nothing. actual job is in flushed(changesTracker) 
  }

  /**
   * Called directly by transient session
   * 
   * @param changesTracker changes tracker to dispose after async job
   */
  public void flushed(@NotNull TransientChangesTracker changesTracker, @Nullable Set<TransientEntityChange> changes) {
    this.fire(Where.SYNC_AFTER_FLUSH, changes);
    this.asyncFire(changes, changesTracker);
  }

  public void beforeFlush(@Nullable Set<TransientEntityChange> changes) {
    this.fire(Where.SYNC_BEFORE_CONSTRAINTS, changes);
  }

  public void beforeFlushAfterConstraintsCheck(@Nullable Set<TransientEntityChange> changes) {
    this.fire(Where.SYNC_BEFORE_FLUSH, changes);
  }

  public void afterConstraintsFail(@NotNull Set<DataIntegrityViolationException> exceptions) {
  }

  private void asyncFire(final Set<TransientEntityChange> changes, TransientChangesTracker changesTracker) {
    getAsyncJobProcessor().queue(new EventsMultiplexer.JobImpl(this, changes, changesTracker));
  }

  private void fire(Where where, Set<TransientEntityChange> changes) {
    for (TransientEntityChange c : changes) {
      this.handlePerEntityChanges(where, c);
      this.handlePerEntityTypeChanges(where, c);
    }
  }

  public void addListener(final Entity e, final _FunctionTypes._void_P1_E0<? super Entity> l) {
    // for backward compatibility 
    this.addListener(e, new EntityAdapter() {
      public void updatedSync(Entity old, Entity current) {
        l.invoke(e);
      }
    });
  }

  public void addListener(Entity e, IEntityListener listener) {
    // typecast to disable generator hook 
    if ((Object) e == null || listener == null) {
      if (log.isWarnEnabled()) {
        log.warn("Can't add null listener to null entity");
      }
      return;
    }
    if (((TransientEntity) e).isNew()) {
      throw new IllegalStateException("Entity is not saved into database - you can't listern to it.");
    }
    final EventsMultiplexer.FullEntityId id = new EventsMultiplexer.FullEntityId(e.getStore(), e.getId());
    this.rwl.writeLock().lock();
    try {
      if (open) {
        Queue<IEntityListener> listeners = this.instanceToListeners.get(id);
        if (listeners == null) {
          listeners = new ConcurrentLinkedQueue<IEntityListener>();
          this.instanceToListeners.put(id, listeners);
        }
        listeners.add(listener);
      }
    } finally {
      this.rwl.writeLock().unlock();
    }
  }

  public void removeListener(Entity e, IEntityListener listener) {
    // typecast to disable generator hook 
    if ((Object) e == null || listener == null) {
      if (log.isWarnEnabled()) {
        log.warn("Can't remove null listener from null entity");
      }
      return;
    }
    final EventsMultiplexer.FullEntityId id = new EventsMultiplexer.FullEntityId(e.getStore(), e.getId());
    this.rwl.writeLock().lock();
    try {
      final Queue<IEntityListener> listeners = this.instanceToListeners.get(id);
      if (listeners != null) {
        listeners.remove(listener);
        if (listeners.size() == 0) {
          this.instanceToListeners.remove(id);
        }
      }
    } finally {
      this.rwl.writeLock().unlock();
    }
  }

  public void addListener(String entityType, IEntityListener listener) {
    //  ensure that this code will be executed outside of transaction 
    this.rwl.writeLock().lock();
    try {
      if (open) {
        Queue<IEntityListener> listeners = this.typeToListeners.get(entityType);
        if (listeners == null) {
          listeners = new ConcurrentLinkedQueue<IEntityListener>();
          this.typeToListeners.put(entityType, listeners);
        }
        listeners.add(listener);
      }
    } finally {
      this.rwl.writeLock().unlock();
    }
  }

  public void removeListener(String entityType, IEntityListener listener) {
    this.rwl.writeLock().lock();
    try {
      if (open) {
        Queue<IEntityListener> listeners = this.typeToListeners.get(entityType);
        if (listeners != null) {
          listeners.remove(listener);
          if (listeners.size() == 0) {
            this.typeToListeners.remove(entityType);
          }
        }
      }
    } finally {
      this.rwl.writeLock().unlock();
    }
  }

  public void close() {
    if (log.isInfoEnabled()) {
      log.info("Cleaning EventsMultiplexer listeners");
    }
    this.rwl.writeLock().lock();
    open = false;
    final Set<EventsMultiplexer.FullEntityId> notClosedListeners;
    // clear listeners 
    try {
      this.typeToListeners.clear();
      // copy set 
      notClosedListeners = new HashSet<EventsMultiplexer.FullEntityId>(this.instanceToListeners.keySet());
      instanceToListeners.clear();
    } finally {
      this.rwl.writeLock().unlock();
    }
    for (final EventsMultiplexer.FullEntityId id : notClosedListeners) {
      if (log.isWarnEnabled()) {
        log.warn(listenerToString(id, this.instanceToListeners.get(id)));
      }
    }
    // all processors should be finished before 
    int processors = store2processor.size();
    if (processors > 0) {
      if (log.isWarnEnabled()) {
        log.warn("Active job processors on eventMultiplexer close: " + processors);
      }
    }
  }

  public void finishJobProcessor(TransientEntityStore store) {
    if (log.isInfoEnabled()) {
      log.info("Finishing EventsMultiplexer job processor for store " + store);
    }
    DelegatingJobProcessor<ThreadJobProcessor> processor = store2processor.get(store);
    if (processor == null) {
      if (log.isWarnEnabled()) {
        log.warn("Job processor for store " + store + " not found");
      }
    }
    store2processor.remove(store);
    processor.finish();
    if (log.isInfoEnabled()) {
      log.info("EventsMultiplexer closed. Jobs count: " + processor.pendingJobs() + "/" + processor.pendingTimedJobs());
    }
  }

  public boolean hasEntityListeners() {
    this.rwl.readLock().lock();
    try {
      return !(instanceToListeners.isEmpty());
    } finally {
      this.rwl.readLock().unlock();
    }
  }

  private String listenerToString(final EventsMultiplexer.FullEntityId id, Queue<IEntityListener> listeners) {
    final StringBuilder builder = new StringBuilder(40);
    builder.append("Unregistered entity to listener class: ");
    id.toString(builder);
    builder.append(" ->");
    for (IEntityListener listener : listeners) {
      builder.append(' ');
      builder.append(listener.getClass().getName());
    }
    return builder.toString();
  }

  private void handlePerEntityChanges(Where where, TransientEntityChange c) {
    final Queue<IEntityListener> listeners;
    final TransientEntity e = c.getTransientEntity();
    final EventsMultiplexer.FullEntityId id = new EventsMultiplexer.FullEntityId(e.getStore(), e.getId());
    if (where == Where.ASYNC_AFTER_FLUSH && c.getChangeType() == EntityChangeType.REMOVE) {
      // unsubscribe all entity listeners, but fire them anyway 
      this.rwl.writeLock().lock();
      try {
        listeners = this.instanceToListeners.remove(id);
      } finally {
        this.rwl.writeLock().unlock();
      }
    } else {
      this.rwl.readLock().lock();
      try {
        listeners = this.instanceToListeners.get(id);
      } finally {
        this.rwl.readLock().unlock();
      }
    }
    this.handleChange(where, c, listeners);
  }

  private void handlePerEntityTypeChanges(Where where, TransientEntityChange c) {
    ModelMetaData modelMedatData = (((ModelMetaData) ServiceLocator.getOptionalBean("modelMetaData")));
    if (modelMedatData != null) {
      EntityMetaData emd = modelMedatData.getEntityMetaData(c.getTransientEntity().getType());
      if (emd != null) {
        for (String type : emd.getThisAndSuperTypes()) {
          Queue<IEntityListener> listeners = null;
          this.rwl.readLock().lock();
          try {
            listeners = this.typeToListeners.get(type);
          } finally {
            this.rwl.readLock().unlock();
          }
          this.handleChange(where, c, listeners);
        }
      }
    }
  }

  private void handleChange(Where where, TransientEntityChange c, Queue<IEntityListener> listeners) {
    if (listeners != null) {
      for (IEntityListener l : listeners) {
        try {
          switch (where) {
            case SYNC_BEFORE_CONSTRAINTS:
              switch (c.getChangeType()) {
                case ADD:
                  l.addedSyncBeforeConstraints(c.getTransientEntity());
                  break;
                case UPDATE:
                  l.updatedSyncBeforeConstraints(c.getSnaphotEntity(), c.getTransientEntity());
                  break;
                case REMOVE:
                  l.removedSyncBeforeConstraints(c.getSnaphotEntity());
                  break;
                default:
                  throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
              }
              break;
            case SYNC_BEFORE_FLUSH:
              switch (c.getChangeType()) {
                case ADD:
                  l.addedSyncBeforeFlush(c.getTransientEntity());
                  break;
                case UPDATE:
                  l.updatedSyncBeforeFlush(c.getSnaphotEntity(), c.getTransientEntity());
                  break;
                case REMOVE:
                  l.removedSyncBeforeFlush(c.getSnaphotEntity());
                  break;
                default:
                  throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
              }
              break;
            case SYNC_AFTER_FLUSH:
              switch (c.getChangeType()) {
                case ADD:
                  l.addedSync(c.getTransientEntity());
                  break;
                case UPDATE:
                  l.updatedSync(c.getSnaphotEntity(), c.getTransientEntity());
                  break;
                case REMOVE:
                  l.removedSync(c.getSnaphotEntity());
                  break;
                default:
                  throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
              }
              break;
            case ASYNC_AFTER_FLUSH:
              switch (c.getChangeType()) {
                case ADD:
                  l.addedAsync(c.getTransientEntity());
                  break;
                case UPDATE:
                  l.updatedAsync(c.getSnaphotEntity(), c.getTransientEntity());
                  break;
                case REMOVE:
                  l.removedAsync(c.getSnaphotEntity());
                  break;
                default:
                  throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
              }
              break;
            default:
              throw new IllegalArgumentException("Illegal arguments " + where + ":" + c.getChangeType());
          }
        } catch (Exception e) {
          if (log.isErrorEnabled()) {
            log.error("Exception while notifying entity listener.", e);
          }
          // rethrow exception only for beforeFlush listeners 
          if (where == Where.SYNC_BEFORE_CONSTRAINTS) {
            if (e instanceof RuntimeException) {
              throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
          }
        }
      }
    }
  }

  public DelegatingJobProcessor<ThreadJobProcessor> getAsyncJobProcessor() {
    TransientEntityStore store = ((TransientEntityStore) ServiceLocator.getBean("transientEntityStore"));
    DelegatingJobProcessor<ThreadJobProcessor> processor = store2processor.get(store);
    if (processor == null) {
      DelegatingJobProcessor<ThreadJobProcessor> newProcessor = createJobProcessor();
      processor = store2processor.putIfAbsent(store, newProcessor);
      if (processor == null) {
        processor = newProcessor;
      }
    }
    return processor;
  }

  @Nullable
  public static EventsMultiplexer getInstance() {
    // this method may be called by global beans on global scope shutdown 
    // as a result, eventsMultiplexer may be removed already 
    try {
      return ((EventsMultiplexer) ServiceLocator.getBean("eventsMultiplexer"));
    } catch (Exception e) {
      if (log.isWarnEnabled()) {
        log.warn("Can't access events multiplexer: " + e.getClass().getName() + ": " + e.getMessage());
      }
      return null;
    }
  }

  public static void removeListenerSafe(Entity e, IEntityListener listener) {
    check_9klgcu_a0a1(getInstance(), e, listener);
  }

  public static void removeListenerSafe(String type, IEntityListener listener) {
    check_9klgcu_a0a2(getInstance(), type, listener);
  }

  private static DelegatingJobProcessor<ThreadJobProcessor> createJobProcessor() {
    DelegatingJobProcessor<ThreadJobProcessor> processor = (((DelegatingJobProcessor<ThreadJobProcessor>) ServiceLocator.getOptionalBean("eventsMultiplexerJobProcessor")));
    if (processor == null) {
      processor = new DelegatingJobProcessor<ThreadJobProcessor>(ThreadJobProcessorPool.getOrCreateJobProcessor("EventsMultiplexerJobProcessor"));
    }
    processor.setExceptionHandler(new ExceptionHandlerImpl());
    processor.start();
    return processor;
  }

  private static void check_9klgcu_a0a1(EventsMultiplexer checkedDotOperand, Entity e, IEntityListener listener) {
    if (null != checkedDotOperand) {
      checkedDotOperand.removeListener(e, listener);
    }

  }

  private static void check_9klgcu_a0a2(EventsMultiplexer checkedDotOperand, String type, IEntityListener listener) {
    if (null != checkedDotOperand) {
      checkedDotOperand.removeListener(type, listener);
    }

  }

  private static class JobImpl extends Job {
    private Set<TransientEntityChange> changes;
    private TransientChangesTracker changesTracker;
    private EventsMultiplexer eventsMultiplexer;

    public JobImpl(EventsMultiplexer eventsMultiplexer, Set<TransientEntityChange> changes, TransientChangesTracker changesTracker) {
      this.eventsMultiplexer = eventsMultiplexer;
      this.changes = changes;
      this.changesTracker = changesTracker;
    }

    public void execute() throws Throwable {
      try {
        _Txn.run(new _FunctionTypes._void_P0_E0() {
          public void invoke() {
            JobImpl.this.eventsMultiplexer.fire(Where.ASYNC_AFTER_FLUSH, JobImpl.this.changes);
            return;
          }
        });
      } finally {
        changesTracker.dispose();
      }
    }

    @Override
    public String getName() {
      return "Async events from EventMultiplexer";
    }

    @Override
    public String getGroup() {
      return changesTracker.getSnapshot().getStore().getLocation();
    }
  }

  private class FullEntityId {
    private final int storeHashCode;
    private final int entityTypeId;
    private final long entityLocalId;

    private FullEntityId(final EntityStore store, final EntityId id) {
      storeHashCode = System.identityHashCode(store);
      entityTypeId = id.getTypeId();
      entityLocalId = id.getLocalId();
    }

    @Override
    public boolean equals(Object object) {
      if (this == object) {
        return true;
      }
      EventsMultiplexer.FullEntityId that = (EventsMultiplexer.FullEntityId) object;
      if (storeHashCode != that.storeHashCode) {
        return false;
      }
      if (entityLocalId != that.entityLocalId) {
        return false;
      }
      if (entityTypeId != that.entityTypeId) {
        return false;
      }
      return true;
    }

    @Override
    public int hashCode() {
      int result = storeHashCode;
      result = 31 * result + entityTypeId;
      result = 31 * result + (int) (entityLocalId ^ (entityLocalId >> 32));
      return result;
    }

    @Override
    public String toString() {
      final StringBuilder builder = new StringBuilder(10);
      toString(builder);
      return builder.toString();
    }

    public void toString(final StringBuilder builder) {
      builder.append(entityTypeId);
      builder.append('-');
      builder.append(entityLocalId);
      builder.append('@');
      builder.append(storeHashCode);
    }
  }
}
