package org.elasticsearch.client.http.support;

import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.HttpClusterAdminActionModule;
import org.elasticsearch.action.support.HttpAction;
import org.elasticsearch.action.support.HttpClient;
import org.elasticsearch.client.GenericClient;
import org.elasticsearch.common.settings.Settings;

public class InternalHttpClusterAdminClient extends HttpClient {

    private final HttpClusterAdminActionModule actions;
    
    public InternalHttpClusterAdminClient(Settings settings, HttpClusterAdminActionModule actions) {
        super(settings);
        this.actions = actions;
    }
    
    public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>, Client extends GenericClient> 
            ActionFuture<Response> execute(Action<Request, Response, RequestBuilder, Client> action, Request request) {
        HttpAction<Request,Response> httpAction = actions.getAction(action.name());
        return httpAction.execute(this, request, null);
    }

    public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>, Client extends GenericClient> 
            void execute(Action<Request, Response, RequestBuilder, Client> action, Request request, ActionListener<Response> listener) {
        HttpAction<Request, Response> httpAction = actions.getAction(action.name());
        httpAction.execute(this, request, listener);
    }

    public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>, Client extends GenericClient> 
            RequestBuilder prepareExecute(Action<Request, Response, RequestBuilder, Client> action) {
        return action.newRequestBuilder((Client)this);
    }
    
}
