## Guava EventBus 源码解析

1. 创建 EventBus 相关代码没啥好说的 比较简单

```java
   public class EventBus {

    public EventBus() {
        this("default");
    }

    public EventBus(String identifier) {
        this(
                identifier,
                MoreExecutors.directExecutor(),
                Dispatcher.perThreadDispatchQueue(),
                LoggingHandler.INSTANCE
        );
    }

    EventBus(
            String identifier,
            Executor executor,
            Dispatcher dispatcher,
            SubscriberExceptionHandler exceptionHandler) {
        this.identifier = checkNotNull(identifier);
        this.executor = checkNotNull(executor);
        this.dispatcher = checkNotNull(dispatcher);
        this.exceptionHandler = checkNotNull(exceptionHandler);
    }


}
```

2. 注册订阅 和 取消订阅

```java
// subscribers = 
public class EventBus {
    /**
     * 注册所有订户方法以接收 {@code object} 事件.
     *
     * @param object object whose subscriber methods should be registered.
     */
    public void register(Object object) {
        subscribers.register(object);
    }

    /**
     * 和上面类似 没啥好说的
     * @param object 对象
     */
    public void unregister(Object object) {
        subscribers.unregister(object);
    }
}

public class SubscriberRegistry {
    void register(Object listener) {
        Multimap<Class<?>, Subscriber> listenerMethods = findAllSubscribers(listener);

        for (Entry<Class<?>, Collection<Subscriber>> entry : listenerMethods.asMap().entrySet()) {
            Class<?> eventType = entry.getKey();
            Collection<Subscriber> eventMethodsInListener = entry.getValue();

            // 判断, 此消息类型是否第一次注册
            CopyOnWriteArraySet<Subscriber> eventSubscribers = subscribers.get(eventType);

            // 第一次注册, 初始化信息
            if (eventSubscribers == null) {
                CopyOnWriteArraySet<Subscriber> newSet = new CopyOnWriteArraySet<>();
                eventSubscribers =
                        MoreObjects.firstNonNull(subscribers.putIfAbsent(eventType, newSet), newSet);
            }
            // 添加到订阅
            eventSubscribers.addAll(eventMethodsInListener);
        }
    }

    private Multimap<Class<?>, Subscriber> findAllSubscribers(Object listener) {
        // 创建一个 Map
        // multimap不存储重复的键值对。添加与现有键值对相等的新键值对没有效果。  键和值可能为null。支持所有可选的multimap方法，并且所有返回的视图都是可修改的。
        // 当任何并发操作更新multimap时，此类不是线程安全的。如果最后一次写入，并发读取操作将正常工作发生-之前任何读数。
        Multimap<Class<?>, Subscriber> methodsInListener = HashMultimap.create();
        // 获取注册进来的Class
        Class<?> clazz = listener.getClass();
        // 获取方法
        for (Method method : getAnnotatedMethods(clazz)) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            // 取第一个参数作为 EventBus 的入参类型匹配
            Class<?> eventType = parameterTypes[0];
            // 注册
            methodsInListener.put(eventType, Subscriber.create(bus, listener, method));
        }
        return methodsInListener;
    }
}
```

3. 发送消息

```java
public class EventBus {
    public void post(Object event) {
        //  根据消息类型, 获取注册的 Subscriber
        Iterator<Subscriber> eventSubscribers = subscribers.getSubscribers(event);
        if (eventSubscribers.hasNext()) {
            dispatcher.dispatch(event, eventSubscribers);
            // 如果 无队列可消费, 递归等待队列
        } else if (!(event instanceof DeadEvent)) {
            // the event had no subscribers and was not itself a DeadEvent
            post(new DeadEvent(this, event));
        }
    }
}
```

```java
public class Dispatcher {
    void dispatch(Object event, Iterator<Subscriber> subscribers) {
        checkNotNull(event);
        checkNotNull(subscribers);
        // 获取队列
        Queue<Event> queueForThread = queue.get();
        // 将消息添加到队列中
        queueForThread.offer(new Event(event, subscribers));

        if (!dispatching.get()) {
            dispatching.set(true);
            try {
                Event nextEvent;
                // 判断有消息
                while ((nextEvent = queueForThread.poll()) != null) {
                    // 判断有消费者
                    while (nextEvent.subscribers.hasNext()) {
                        // dispatchEvent, DirectExecutor.run 
                        nextEvent.subscribers.next().dispatchEvent(nextEvent.event);
                    }
                }
            } finally {
                dispatching.remove();
                queue.remove();
            }
        }
    }
}
```
```java
public class Subscriber {
    final void dispatchEvent(Object event) {
        executor.execute(
                // Runner Lambda
                () -> {
                    try {
                        invokeSubscriberMethod(event);
                        // 这段就是 invoke 反射, 拿到注册进来的方法, 然后调用方法, 参数就是 post 传进来的
                        //  method.invoke(target, checkNotNull(event));
                    } catch (InvocationTargetException e) {
                        bus.handleSubscriberException(e.getCause(), context(event));
                    }
                }
        );
    }
}
```

## 看个完整版的
// EventBus
```java
/*
 * Copyright (C) 2007 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.eventbus;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.MoreExecutors;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dispatches events to listeners, and provides ways for listeners to register themselves.
 *
 * <h2>Avoid EventBus</h2>
 *
 * <p><b>We recommend against using EventBus.</b> It was designed many years ago, and newer
 * libraries offer better ways to decouple components and react to events.
 *
 * <p>To decouple components, we recommend a dependency-injection framework. For Android code, most
 * apps use <a href="https://dagger.dev">Dagger</a>. For server code, common options include <a
 * href="https://github.com/google/guice/wiki/Motivation">Guice</a> and <a
 * href="https://docs.spring.io/spring-framework/docs/current/reference/html/core.html#beans-introduction">Spring</a>.
 * Frameworks typically offer a way to register multiple listeners independently and then request
 * them together as a set (<a href="https://dagger.dev/dev-guide/multibindings">Dagger</a>, <a
 * href="https://github.com/google/guice/wiki/Multibindings">Guice</a>, <a
 * href="https://docs.spring.io/spring-framework/docs/current/reference/html/core.html#beans-autowired-annotation">Spring</a>).
 *
 * <p>To react to events, we recommend a reactive-streams framework like <a
 * href="https://github.com/ReactiveX/RxJava/wiki">RxJava</a> (supplemented with its <a
 * href="https://github.com/ReactiveX/RxAndroid">RxAndroid</a> extension if you are building for
 * Android) or <a href="https://projectreactor.io/">Project Reactor</a>. (For the basics of
 * translating code from using an event bus to using a reactive-streams framework, see these two
 * guides: <a href="https://blog.jkl.gg/implementing-an-event-bus-with-rxjava-rxbus/">1</a>, <a
 * href="https://lorentzos.com/rxjava-as-event-bus-the-right-way-10a36bdd49ba">2</a>.) Some usages
 * of EventBus may be better written using <a
 * href="https://kotlinlang.org/docs/coroutines-guide.html">Kotlin coroutines</a>, including <a
 * href="https://kotlinlang.org/docs/flow.html">Flow</a> and <a
 * href="https://kotlinlang.org/docs/channels.html">Channels</a>. Yet other usages are better served
 * by individual libraries that provide specialized support for particular use cases.
 *
 * <p>Disadvantages of EventBus include:
 *
 * <ul>
 *   <li>It makes the cross-references between producer and subscriber harder to find. This can
 *       complicate debugging, lead to unintentional reentrant calls, and force apps to eagerly
 *       initialize all possible subscribers at startup time.
 *   <li>It uses reflection in ways that break when code is processed by optimizers/minimizers like
 *       <a href="https://developer.android.com/studio/build/shrink-code">R8 and Proguard</a>.
 *   <li>It doesn't offer a way to wait for multiple events before taking action. For example, it
 *       doesn't offer a way to wait for multiple producers to all report that they're "ready," nor
 *       does it offer a way to batch multiple events from a single producer together.
 *   <li>It doesn't support backpressure and other features needed for resilience.
 *   <li>It doesn't provide much control of threading.
 *   <li>It doesn't offer much monitoring.
 *   <li>It doesn't propagate exceptions, so apps don't have a way to react to them.
 *   <li>It doesn't interoperate well with RxJava, coroutines, and other more commonly used
 *       alternatives.
 *   <li>It imposes requirements on the lifecycle of its subscribers. For example, if an event
 *       occurs between when one subscriber is removed and the next subscriber is added, the event
 *       is dropped.
 *   <li>Its performance is suboptimal, especially under Android.
 *   <li>It <a href="https://github.com/google/guava/issues/1431">doesn't support parameterized
 *       types</a>.
 *   <li>With the introduction of lambdas in Java 8, EventBus went from less verbose than listeners
 *       to <a href="https://github.com/google/guava/issues/3311">more verbose</a>.
 * </ul>
 *
 * <h2>EventBus Summary</h2>
 *
 * <p>The EventBus allows publish-subscribe-style communication between components without requiring
 * the components to explicitly register with one another (and thus be aware of each other). It is
 * designed exclusively to replace traditional Java in-process event distribution using explicit
 * registration. It is <em>not</em> a general-purpose publish-subscribe system, nor is it intended
 * for interprocess communication.
 *
 * <h2>Receiving Events</h2>
 *
 * <p>To receive events, an object should:
 *
 * <ol>
 *   <li>Expose a public method, known as the <i>event subscriber</i>, which accepts a single
 *       argument of the type of event desired;
 *   <li>Mark it with a {@link Subscribe} annotation;
 *   <li>Pass itself to an EventBus instance's {@link #register(Object)} method.
 * </ol>
 *
 * <h2>Posting Events</h2>
 *
 * <p>To post an event, simply provide the event object to the {@link #post(Object)} method. The
 * EventBus instance will determine the type of event and route it to all registered listeners.
 *
 * <p>Events are routed based on their type &mdash; an event will be delivered to any subscriber for
 * any type to which the event is <em>assignable.</em> This includes implemented interfaces, all
 * superclasses, and all interfaces implemented by superclasses.
 *
 * <p>When {@code post} is called, all registered subscribers for an event are run in sequence, so
 * subscribers should be reasonably quick. If an event may trigger an extended process (such as a
 * database load), spawn a thread or queue it for later. (For a convenient way to do this, use an
 * {@link AsyncEventBus}.)
 *
 * <h2>Subscriber Methods</h2>
 *
 * <p>Event subscriber methods must accept only one argument: the event.
 *
 * <p>Subscribers should not, in general, throw. If they do, the EventBus will catch and log the
 * exception. This is rarely the right solution for error handling and should not be relied upon; it
 * is intended solely to help find problems during development.
 *
 * <p>The EventBus guarantees that it will not call a subscriber method from multiple threads
 * simultaneously, unless the method explicitly allows it by bearing the {@link
 * AllowConcurrentEvents} annotation. If this annotation is not present, subscriber methods need not
 * worry about being reentrant, unless also called from outside the EventBus.
 *
 * <h2>Dead Events</h2>
 *
 * <p>If an event is posted, but no registered subscribers can accept it, it is considered "dead."
 * To give the system a second chance to handle dead events, they are wrapped in an instance of
 * {@link DeadEvent} and reposted.
 *
 * <p>If a subscriber for a supertype of all events (such as Object) is registered, no event will
 * ever be considered dead, and no DeadEvents will be generated. Accordingly, while DeadEvent
 * extends {@link Object}, a subscriber registered to receive any Object will never receive a
 * DeadEvent.
 *
 * <p>This class is safe for concurrent use.
 *
 * <p>See the Guava User Guide article on <a
 * href="https://github.com/google/guava/wiki/EventBusExplained">{@code EventBus}</a>.
 *
 * @author Cliff Biffle
 * @since 10.0
 */
@ElementTypesAreNonnullByDefault
public class EventBus {

  private static final Logger logger = Logger.getLogger(EventBus.class.getName());

  private final String identifier;
  private final Executor executor;
  private final SubscriberExceptionHandler exceptionHandler;

  private final SubscriberRegistry subscribers = new SubscriberRegistry(this);
  private final Dispatcher dispatcher;

  /** Creates a new EventBus named "default". */
  public EventBus() {
    this("default");
  }

  /**
   * Creates a new EventBus with the given {@code identifier}.
   *
   * @param identifier a brief name for this bus, for logging purposes. Should be a valid Java
   *     identifier.
   */
  public EventBus(String identifier) {
    this(
        identifier,
        MoreExecutors.directExecutor(),
        Dispatcher.perThreadDispatchQueue(),
        LoggingHandler.INSTANCE);
  }

  /**
   * Creates a new EventBus with the given {@link SubscriberExceptionHandler}.
   *
   * @param exceptionHandler Handler for subscriber exceptions.
   * @since 16.0
   */
  public EventBus(SubscriberExceptionHandler exceptionHandler) {
    this(
        "default",
        MoreExecutors.directExecutor(),
        Dispatcher.perThreadDispatchQueue(),
        exceptionHandler);
  }

  EventBus(
      String identifier,
      Executor executor,
      Dispatcher dispatcher,
      SubscriberExceptionHandler exceptionHandler) {
    this.identifier = checkNotNull(identifier);
    this.executor = checkNotNull(executor);
    this.dispatcher = checkNotNull(dispatcher);
    this.exceptionHandler = checkNotNull(exceptionHandler);
  }

  /**
   * Returns the identifier for this event bus.
   *
   * @since 19.0
   */
  public final String identifier() {
    return identifier;
  }

  /** Returns the default executor this event bus uses for dispatching events to subscribers. */
  final Executor executor() {
    return executor;
  }

  /** Handles the given exception thrown by a subscriber with the given context. */
  void handleSubscriberException(Throwable e, SubscriberExceptionContext context) {
    checkNotNull(e);
    checkNotNull(context);
    try {
      exceptionHandler.handleException(e, context);
    } catch (Throwable e2) {
      // if the handler threw an exception... well, just log it
      logger.log(
          Level.SEVERE,
          String.format(Locale.ROOT, "Exception %s thrown while handling exception: %s", e2, e),
          e2);
    }
  }

  /**
   * Registers all subscriber methods on {@code object} to receive events.
   *
   * @param object object whose subscriber methods should be registered.
   */
  public void register(Object object) {
    subscribers.register(object);
  }

  /**
   * Unregisters all subscriber methods on a registered {@code object}.
   *
   * @param object object whose subscriber methods should be unregistered.
   * @throws IllegalArgumentException if the object was not previously registered.
   */
  public void unregister(Object object) {
    subscribers.unregister(object);
  }

  /**
   * Posts an event to all registered subscribers. This method will return successfully after the
   * event has been posted to all subscribers, and regardless of any exceptions thrown by
   * subscribers.
   *
   * <p>If no subscribers have been subscribed for {@code event}'s class, and {@code event} is not
   * already a {@link DeadEvent}, it will be wrapped in a DeadEvent and reposted.
   *
   * @param event event to post.
   */
  public void post(Object event) {
    Iterator<Subscriber> eventSubscribers = subscribers.getSubscribers(event);
    if (eventSubscribers.hasNext()) {
      dispatcher.dispatch(event, eventSubscribers);
    } else if (!(event instanceof DeadEvent)) {
      // the event had no subscribers and was not itself a DeadEvent
      post(new DeadEvent(this, event));
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).addValue(identifier).toString();
  }

  /** Simple logging handler for subscriber exceptions. */
  static final class LoggingHandler implements SubscriberExceptionHandler {
    static final LoggingHandler INSTANCE = new LoggingHandler();

    @Override
    public void handleException(Throwable exception, SubscriberExceptionContext context) {
      Logger logger = logger(context);
      if (logger.isLoggable(Level.SEVERE)) {
        logger.log(Level.SEVERE, message(context), exception);
      }
    }

    private static Logger logger(SubscriberExceptionContext context) {
      return Logger.getLogger(EventBus.class.getName() + "." + context.getEventBus().identifier());
    }

    private static String message(SubscriberExceptionContext context) {
      Method method = context.getSubscriberMethod();
      return "Exception thrown by subscriber method "
          + method.getName()
          + '('
          + method.getParameterTypes()[0].getName()
          + ')'
          + " on subscriber "
          + context.getSubscriber()
          + " when dispatching event: "
          + context.getEvent();
    }
  }
}
```

// Subscriber
```java
/*
 * Copyright (C) 2014 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.eventbus;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.j2objc.annotations.Weak;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import javax.annotation.CheckForNull;

/**
 * A subscriber method on a specific object, plus the executor that should be used for dispatching
 * events to it.
 *
 * <p>Two subscribers are equivalent when they refer to the same method on the same object (not
 * class). This property is used to ensure that no subscriber method is registered more than once.
 *
 * @author Colin Decker
 */
@ElementTypesAreNonnullByDefault
class Subscriber {

  /** Creates a {@code Subscriber} for {@code method} on {@code listener}. */
  static Subscriber create(EventBus bus, Object listener, Method method) {
    return isDeclaredThreadSafe(method)
        ? new Subscriber(bus, listener, method)
        : new SynchronizedSubscriber(bus, listener, method);
  }

  /** The event bus this subscriber belongs to. */
  @Weak private EventBus bus;

  /** The object with the subscriber method. */
  @VisibleForTesting final Object target;

  /** Subscriber method. */
  private final Method method;

  /** Executor to use for dispatching events to this subscriber. */
  private final Executor executor;

  private Subscriber(EventBus bus, Object target, Method method) {
    this.bus = bus;
    this.target = checkNotNull(target);
    this.method = method;
    method.setAccessible(true);

    this.executor = bus.executor();
  }

  /** Dispatches {@code event} to this subscriber using the proper executor. */
  final void dispatchEvent(Object event) {
    executor.execute(
        () -> {
          try {
            invokeSubscriberMethod(event);
          } catch (InvocationTargetException e) {
            bus.handleSubscriberException(e.getCause(), context(event));
          }
        });
  }

  /**
   * Invokes the subscriber method. This method can be overridden to make the invocation
   * synchronized.
   */
  @VisibleForTesting
  void invokeSubscriberMethod(Object event) throws InvocationTargetException {
    try {
      method.invoke(target, checkNotNull(event));
    } catch (IllegalArgumentException e) {
      throw new Error("Method rejected target/argument: " + event, e);
    } catch (IllegalAccessException e) {
      throw new Error("Method became inaccessible: " + event, e);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Error) {
        throw (Error) e.getCause();
      }
      throw e;
    }
  }

  /** Gets the context for the given event. */
  private SubscriberExceptionContext context(Object event) {
    return new SubscriberExceptionContext(bus, event, target, method);
  }

  @Override
  public final int hashCode() {
    return (31 + method.hashCode()) * 31 + System.identityHashCode(target);
  }

  @Override
  public final boolean equals(@CheckForNull Object obj) {
    if (obj instanceof Subscriber) {
      Subscriber that = (Subscriber) obj;
      // Use == so that different equal instances will still receive events.
      // We only guard against the case that the same object is registered
      // multiple times
      return target == that.target && method.equals(that.method);
    }
    return false;
  }

  /**
   * Checks whether {@code method} is thread-safe, as indicated by the presence of the {@link
   * AllowConcurrentEvents} annotation.
   */
  private static boolean isDeclaredThreadSafe(Method method) {
    return method.getAnnotation(AllowConcurrentEvents.class) != null;
  }

  /**
   * Subscriber that synchronizes invocations of a method to ensure that only one thread may enter
   * the method at a time.
   */
  @VisibleForTesting
  static final class SynchronizedSubscriber extends Subscriber {

    private SynchronizedSubscriber(EventBus bus, Object target, Method method) {
      super(bus, target, method);
    }

    @Override
    void invokeSubscriberMethod(Object event) throws InvocationTargetException {
      synchronized (this) {
        super.invokeSubscriberMethod(event);
      }
    }
  }
}
```

// Dispatcher
```java
/*
 * Copyright (C) 2014 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.eventbus;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.Queues;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Handler for dispatching events to subscribers, providing different event ordering guarantees that
 * make sense for different situations.
 *
 * <p><b>Note:</b> The dispatcher is orthogonal to the subscriber's {@code Executor}. The dispatcher
 * controls the order in which events are dispatched, while the executor controls how (i.e. on which
 * thread) the subscriber is actually called when an event is dispatched to it.
 *
 * @author Colin Decker
 */
@ElementTypesAreNonnullByDefault
abstract class Dispatcher {

  /**
   * Returns a dispatcher that queues events that are posted reentrantly on a thread that is already
   * dispatching an event, guaranteeing that all events posted on a single thread are dispatched to
   * all subscribers in the order they are posted.
   *
   * <p>When all subscribers are dispatched to using a <i>direct</i> executor (which dispatches on
   * the same thread that posts the event), this yields a breadth-first dispatch order on each
   * thread. That is, all subscribers to a single event A will be called before any subscribers to
   * any events B and C that are posted to the event bus by the subscribers to A.
   */
  static Dispatcher perThreadDispatchQueue() {
    return new PerThreadQueuedDispatcher();
  }

  /**
   * Returns a dispatcher that queues events that are posted in a single global queue. This behavior
   * matches the original behavior of AsyncEventBus exactly, but is otherwise not especially useful.
   * For async dispatch, an {@linkplain #immediate() immediate} dispatcher should generally be
   * preferable.
   */
  static Dispatcher legacyAsync() {
    return new LegacyAsyncDispatcher();
  }

  /**
   * Returns a dispatcher that dispatches events to subscribers immediately as they're posted
   * without using an intermediate queue to change the dispatch order. This is effectively a
   * depth-first dispatch order, vs. breadth-first when using a queue.
   */
  static Dispatcher immediate() {
    return ImmediateDispatcher.INSTANCE;
  }

  /** Dispatches the given {@code event} to the given {@code subscribers}. */
  abstract void dispatch(Object event, Iterator<Subscriber> subscribers);

  /** Implementation of a {@link #perThreadDispatchQueue()} dispatcher. */
  private static final class PerThreadQueuedDispatcher extends Dispatcher {

    // This dispatcher matches the original dispatch behavior of EventBus.

    /** Per-thread queue of events to dispatch. */
    private final ThreadLocal<Queue<Event>> queue =
        new ThreadLocal<Queue<Event>>() {
          @Override
          protected Queue<Event> initialValue() {
            return Queues.newArrayDeque();
          }
        };

    /** Per-thread dispatch state, used to avoid reentrant event dispatching. */
    private final ThreadLocal<Boolean> dispatching =
        new ThreadLocal<Boolean>() {
          @Override
          protected Boolean initialValue() {
            return false;
          }
        };

    @Override
    void dispatch(Object event, Iterator<Subscriber> subscribers) {
      checkNotNull(event);
      checkNotNull(subscribers);
      Queue<Event> queueForThread = queue.get();
      queueForThread.offer(new Event(event, subscribers));

      if (!dispatching.get()) {
        dispatching.set(true);
        try {
          Event nextEvent;
          while ((nextEvent = queueForThread.poll()) != null) {
            while (nextEvent.subscribers.hasNext()) {
              nextEvent.subscribers.next().dispatchEvent(nextEvent.event);
            }
          }
        } finally {
          dispatching.remove();
          queue.remove();
        }
      }
    }

    private static final class Event {
      private final Object event;
      private final Iterator<Subscriber> subscribers;

      private Event(Object event, Iterator<Subscriber> subscribers) {
        this.event = event;
        this.subscribers = subscribers;
      }
    }
  }

  /** Implementation of a {@link #legacyAsync()} dispatcher. */
  private static final class LegacyAsyncDispatcher extends Dispatcher {

    // This dispatcher matches the original dispatch behavior of AsyncEventBus.
    //
    // We can't really make any guarantees about the overall dispatch order for this dispatcher in
    // a multithreaded environment for a couple reasons:
    //
    // 1. Subscribers to events posted on different threads can be interleaved with each other
    //    freely. (A event on one thread, B event on another could yield any of
    //    [a1, a2, a3, b1, b2], [a1, b2, a2, a3, b2], [a1, b2, b3, a2, a3], etc.)
    // 2. It's possible for subscribers to actually be dispatched to in a different order than they
    //    were added to the queue. It's easily possible for one thread to take the head of the
    //    queue, immediately followed by another thread taking the next element in the queue. That
    //    second thread can then dispatch to the subscriber it took before the first thread does.
    //
    // All this makes me really wonder if there's any value in queueing here at all. A dispatcher
    // that simply loops through the subscribers and dispatches the event to each would actually
    // probably provide a stronger order guarantee, though that order would obviously be different
    // in some cases.

    /** Global event queue. */
    private final ConcurrentLinkedQueue<EventWithSubscriber> queue =
        Queues.newConcurrentLinkedQueue();

    @Override
    void dispatch(Object event, Iterator<Subscriber> subscribers) {
      checkNotNull(event);
      while (subscribers.hasNext()) {
        queue.add(new EventWithSubscriber(event, subscribers.next()));
      }

      EventWithSubscriber e;
      while ((e = queue.poll()) != null) {
        e.subscriber.dispatchEvent(e.event);
      }
    }

    private static final class EventWithSubscriber {
      private final Object event;
      private final Subscriber subscriber;

      private EventWithSubscriber(Object event, Subscriber subscriber) {
        this.event = event;
        this.subscriber = subscriber;
      }
    }
  }

  /** Implementation of {@link #immediate()}. */
  private static final class ImmediateDispatcher extends Dispatcher {
    private static final ImmediateDispatcher INSTANCE = new ImmediateDispatcher();

    @Override
    void dispatch(Object event, Iterator<Subscriber> subscribers) {
      checkNotNull(event);
      while (subscribers.hasNext()) {
        subscribers.next().dispatchEvent(event);
      }
    }
  }
}
```
## 总结
好像结束的有点快, 核心类  
1. `EventBus` 看上去没那么重要
2. `Subscriber`
3. `Dispatcher` 

从某些角度来看, 代码加起来不超过400行, 果然精华!!!
从某些角度来看这些代码的 `Style`, emmmm 其实看不惯!
