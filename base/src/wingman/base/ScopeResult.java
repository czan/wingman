package wingman.base;

public class ScopeResult extends Throwable {
    public final Object scopeId;
    public final Object thunk;
    public ScopeResult(Object scopeId, Object thunk) {
        super("This exception is internal to wingman. Ideally, you would never see it!");
        this.scopeId = scopeId;
        this.thunk = thunk;
    }
}
