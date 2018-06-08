package dont_give_up.core;

public class ScopeResult extends Throwable {
    public final Object scopeId;
    public final Object thunk;
    public ScopeResult(Object scopeId, Object thunk) {
        super("This exception is internal to dont-give-up. Ideally, you would never see it!");
        this.scopeId = scopeId;
        this.thunk = thunk;
    }
}
