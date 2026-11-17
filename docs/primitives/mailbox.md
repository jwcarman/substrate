# Mailbox

A Mailbox carries exactly one value from whichever node has it to whichever nodes are waiting
for it. It works like a `CompletableFuture` that spans the cluster: one side creates the mailbox
and waits, and any node can complete it by delivering the value.

Use a Mailbox for request and response across nodes, when the answer may arrive at a different
instance from the one holding the waiting request:

- a user's answer to a prompt, posted to one node, needed by the node streaming to that user;
- the result of a job handed to a worker, returned to the node that asked for it;
- an approval, a callback from an external system, or any other one-time reply.

When there is more than one value, use a [Journal](journal.md); when there is one value that
changes, use an [Atom](atom.md).

## Creating and connecting

```java
@Autowired MailboxFactory mailboxes;

Mailbox<Answer> mailbox =
    mailboxes.create("answer:" + requestId, Answer.class, Duration.ofMinutes(5));
```

The `Duration` is the mailbox's lifetime. It must be greater than zero and at most
`substrate.mailbox.max-ttl` (30 minutes by default), and it is never renewed: after five minutes
this mailbox is gone, delivered or not.

Unlike the other factories, `MailboxFactory.create` does not fail when the name is taken. It
replaces the existing mailbox, discarding any value it held, and starts the new lifetime. Use
names that are unique per exchange, such as a request ID.

The node that delivers usually connects by name:

```java
Mailbox<Answer> mailbox = mailboxes.connect("answer:" + requestId, Answer.class);
```

Its first `deliver` or `subscribe` throws `MailboxNotFoundException` if no mailbox has that name.

## Delivering

```java
mailbox.deliver(answer);
```

`deliver` stores the value and notifies every node. It succeeds once:

- a second `deliver` throws `MailboxFullException`;
- a `deliver` after the lifetime has elapsed, or after `delete`, throws `MailboxExpiredException`.

When several nodes may race to answer, `MailboxFullException` tells the losers that someone else
already did.

## Waiting for the value

```java
try (BlockingSubscription<Answer> sub = mailbox.subscribe()) {
  switch (sub.next(Duration.ofMinutes(5))) {
    case NextResult.Value<Answer>(var answer) -> proceed(answer);
    case NextResult.Timeout<Answer> _ -> giveUp("no answer in time");
    case NextResult.Expired<Answer> _ -> giveUp("mailbox expired");
    case NextResult.Deleted<Answer> _ -> giveUp("request cancelled");
    case NextResult.Errored<Answer>(var cause) -> giveUp(cause.getMessage());
    case NextResult.Completed<Answer> _, NextResult.Cancelled<Answer> _ -> {}
  }
}
```

If the value was delivered before you subscribed, the first `next` returns it at once; otherwise
it waits for delivery. After the value, the subscription ends with `Completed`. A single call to
`next` is usually all you need.

Every subscriber gets the value: several nodes can wait on the same mailbox, and a subscriber
that arrives after delivery still receives it, for as long as the mailbox lives.

With callbacks, `onNext` runs once with the value, then `onCompleted`:

```java
mailbox.subscribe((SubscriberConfig<Answer> cfg) -> cfg
    .onNext(this::proceed)
    .onExpired(() -> giveUp("mailbox expired"))
    .onDeleted(() -> giveUp("request cancelled")));
```

## Deleting

```java
mailbox.delete();
```

`delete` removes the mailbox and any value in it. Waiting subscribers end with `Deleted`, and a
later `deliver` throws `MailboxExpiredException`. Deleting a mailbox that doesn't exist does
nothing.

Delete a mailbox once its exchange is over, or to cancel a request: the deleting node's waiters
and any node about to deliver both find out.

## Lifecycle at a glance

| State | `deliver` | Subscribers | Ends when |
|---|---|---|---|
| Empty | stores the value | wait | lifetime elapses, `delete` |
| Delivered | throws `MailboxFullException` | receive the value, then `Completed` | lifetime elapses, `delete` |
| Expired or deleted | throws `MailboxExpiredException` | end with `Expired` or `Deleted` | — |

## Methods

| Method | Does |
|---|---|
| `void deliver(T value)` | Stores the single value. |
| `void delete()` | Removes the mailbox; safe to repeat. |
| `BlockingSubscription<T> subscribe()` | Waits for the value, then completes. |
| `Subscription subscribe(Subscriber<T>)` and `subscribe(Consumer<SubscriberConfig<T>>)` | The callback forms. |
| `String key()` | The backend key. |

## Example: asking the user a question

A node streaming to a browser needs an answer from the user. The browser posts the answer to
whichever node the load balancer picks.

```java
// The streaming node: ask, then wait.
String requestId = UUID.randomUUID().toString();
Mailbox<Answer> mailbox =
    mailboxes.create("answer:" + requestId, Answer.class, Duration.ofMinutes(5));
stream.send(new Question(requestId, "Proceed with the deployment?"));

try (BlockingSubscription<Answer> sub = mailbox.subscribe()) {
  if (sub.next(Duration.ofMinutes(5)) instanceof NextResult.Value<Answer>(var answer)) {
    handle(answer);
  } else {
    handleNoAnswer();
  }
} finally {
  mailbox.delete();
}
```

```java
// Any node: the endpoint the browser posts to.
@PostMapping("/answers/{requestId}")
ResponseEntity<Void> answer(@PathVariable String requestId, @RequestBody Answer answer) {
  try {
    mailboxes.connect("answer:" + requestId, Answer.class).deliver(answer);
    return ResponseEntity.accepted().build();
  } catch (MailboxNotFoundException | MailboxExpiredException _) {
    return ResponseEntity.status(HttpStatus.GONE).build();
  } catch (MailboxFullException _) {
    return ResponseEntity.status(HttpStatus.CONFLICT).build();
  }
}
```
