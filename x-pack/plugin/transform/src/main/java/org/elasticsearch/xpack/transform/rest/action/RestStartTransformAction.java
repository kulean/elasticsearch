/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.transform.rest.action;

import org.elasticsearch.action.support.master.AcknowledgedRequest;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xpack.core.transform.TransformField;
import org.elasticsearch.xpack.core.transform.action.StartTransformAction;

import java.util.List;

import static org.elasticsearch.rest.RestRequest.Method.POST;

public class RestStartTransformAction extends BaseRestHandler {

    @Override
    public List<Route> routes() {
        return List.of(new Route(POST, TransformField.REST_BASE_PATH_TRANSFORMS_BY_ID + "_start"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) {
        String id = restRequest.param(TransformField.ID.getPreferredName());
        TimeValue timeout = restRequest.paramAsTime(TransformField.TIMEOUT.getPreferredName(), AcknowledgedRequest.DEFAULT_ACK_TIMEOUT);

        StartTransformAction.Request request = new StartTransformAction.Request(id, timeout);
        return channel -> client.execute(StartTransformAction.INSTANCE, request, new RestToXContentListener<>(channel));
    }

    @Override
    public String getName() {
        return "transform_start_transform_action";
    }
}
