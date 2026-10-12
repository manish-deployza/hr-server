package com.razorpay;

import org.json.JSONObject;

// Lives in com.razorpay only because the SubscriptionClient constructor is package-private;
// replaces RazorpayClient.subscriptions so RazorpayGatewayService tests never touch the
// network. Each call returns the configured entity, or throws the configured failure.
public class FakeSubscriptionClient extends SubscriptionClient {

    /* Variables */

    public Subscription returned;
    public RazorpayException failure;
    public String lastCancelledId;
    public String lastFetchedId;
    public JSONObject lastCreateRequest;

    /* Constructors */

    public FakeSubscriptionClient() {
        super("fake-auth");
    }

    /* Public Methods */

    @Override
    public Subscription create(JSONObject request) throws RazorpayException {
        lastCreateRequest = request;
        return respond();
    }

    @Override
    public Subscription fetch(String id) throws RazorpayException {
        lastFetchedId = id;
        return respond();
    }

    @Override
    public Subscription cancel(String id) throws RazorpayException {
        lastCancelledId = id;
        return respond();
    }

    /* Private Helper Methods */

    private Subscription respond() throws RazorpayException {
        if (failure != null) {
            throw failure;
        }
        return returned;
    }
}
