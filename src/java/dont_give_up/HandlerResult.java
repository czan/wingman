package dont_give_up;

public class HandlerResult extends Throwable {
    public final Object handlerId;
    public final Object thunk;
    public HandlerResult(Object handlerId, Object thunk) {
        super("Return this result from the handler (if you can see this exception, something has gone wrong)");
        this.handlerId = handlerId;
        this.thunk = thunk;
    }
}
