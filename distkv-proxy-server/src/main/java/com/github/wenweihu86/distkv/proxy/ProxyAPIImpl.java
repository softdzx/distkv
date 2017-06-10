package com.github.wenweihu86.distkv.proxy;

import com.github.wenweihu86.distkv.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Created by wenweihu86 on 2017/6/8.
 */
public class ProxyAPIImpl implements ProxyAPI {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyAPIImpl.class);

    @Override
    public ProxyMessage.SetResponse set(ProxyMessage.SetRequest request) {
        ProxyMessage.SetResponse.Builder responseBuilder = ProxyMessage.SetResponse.newBuilder();
        CommonMessage.BaseResponse.Builder baseResBuilder = CommonMessage.BaseResponse.newBuilder();
        baseResBuilder.setResCode(CommonMessage.ResCode.RES_CODE_FAIL);

        // 随机获取store server的sharding index
        long keySign = ProxyUtils.getMd5Sign(request.getKey().toByteArray());
        GlobalBean globalBean = GlobalBean.getInstance();
        List<ShardingClient> storeServerShardings = globalBean.getStoreServerShardings();
        int randInt = ThreadLocalRandom.current().nextInt(0, storeServerShardings.size());
        ShardingClient storeShardingClient = storeServerShardings.get(randInt);
        int shardingIndex = storeShardingClient.getIndex();

        // 请求meta server，保存keySign -> sharding映射信息
        List<ShardingClient> metaServerShardings = globalBean.getMetaServerShadings();
        int metaShardingId = (int) (keySign % metaServerShardings.size());
        ShardingClient metaShardingClient = metaServerShardings.get(metaShardingId);
        MetaMessage.SetRequest metaRequest = MetaMessage.SetRequest.newBuilder()
                .setKeySign(keySign).setShardingIndex(shardingIndex).build();
        MetaMessage.SetResponse metaResponse = metaShardingClient.getMetaAPI().set(metaRequest);
        if (metaResponse == null
                || metaResponse.getBaseRes().getResCode()
                != CommonMessage.ResCode.RES_CODE_SUCCESS) {
            LOG.warn("meta server set request failed, request={}", ProxyUtils.protoToJson(metaRequest));
            responseBuilder.setBaseRes(baseResBuilder);
            return responseBuilder.build();
        }

        // 请求store server，保存key -> value信息
        StoreMessage.SetRequest storeRequest = StoreMessage.SetRequest.newBuilder()
                .setKey(request.getKey()).setValue(request.getValue()).build();
        StoreMessage.SetResponse storeResponse = storeShardingClient.getStoreAPI().set(storeRequest);
        if (storeResponse == null
                || storeResponse.getBaseRes().getResCode()
                != CommonMessage.ResCode.RES_CODE_SUCCESS) {
            LOG.warn("store server set request failed, request={}", ProxyUtils.protoToJson(storeRequest));
            // 再次请求meta server，删除keySign -> sharding映射信息
            MetaMessage.DeleteRequest deleteRequest = MetaMessage.DeleteRequest.newBuilder()
                    .setKeySign(keySign).build();
            MetaMessage.DeleteResponse deleteResponse
                    = metaShardingClient.getMetaAPI().delete(deleteRequest);
            if (deleteResponse == null
                    || deleteResponse.getBaseRes().getResCode()
                    != CommonMessage.ResCode.RES_CODE_SUCCESS) {
                LOG.warn("meta server delete request failed, request={}", ProxyUtils.protoToJson(deleteRequest));
            }
            responseBuilder.setBaseRes(baseResBuilder);
            return responseBuilder.build();
        }

        responseBuilder.setBaseRes(storeResponse.getBaseRes());
        ProxyMessage.SetResponse response = responseBuilder.build();
        LOG.info("proxy server set request, request={}, response={}",
                ProxyUtils.protoToJson(request),
                ProxyUtils.protoToJson(response));
        return response;
    }

    @Override
    public ProxyMessage.GetResponse get(ProxyMessage.GetRequest request) {
        ProxyMessage.GetResponse.Builder responseBuilder = ProxyMessage.GetResponse.newBuilder();
        CommonMessage.BaseResponse.Builder baseResBuilder = CommonMessage.BaseResponse.newBuilder();
        baseResBuilder.setResCode(CommonMessage.ResCode.RES_CODE_FAIL);

        // 请求meta server，获取keySign -> sharding映射信息
        GlobalBean globalBean = GlobalBean.getInstance();
        List<ShardingClient> metaServerShardings = globalBean.getMetaServerShadings();
        long keySign = ProxyUtils.getMd5Sign(request.getKey().toByteArray());
        int metaShardingId = (int) (keySign % metaServerShardings.size());
        ShardingClient metaShardingClient = metaServerShardings.get(metaShardingId);
        MetaMessage.GetRequest metaRequest = MetaMessage.GetRequest.newBuilder()
                .setKeySign(keySign).build();
        MetaMessage.GetResponse metaResponse = metaShardingClient.getMetaAPI().get(metaRequest);
        if (metaResponse == null
                || metaResponse.getBaseRes().getResCode()
                != CommonMessage.ResCode.RES_CODE_SUCCESS) {
            LOG.warn("meta server get request failed, request={}", ProxyUtils.protoToJson(metaRequest));
            responseBuilder.setBaseRes(baseResBuilder);
            return responseBuilder.build();
        }
        int storeShardingIndex = metaResponse.getShardingIndex();

        // 请求store server，获取key -> value信息
        List<ShardingClient> storeServerShardings = globalBean.getStoreServerShardings();
        if (storeShardingIndex >= storeServerShardings.size()) {
            LOG.warn("storeShardingIndex={} >= storeServerShardingSize={}",
                    storeShardingIndex, storeServerShardings.size());
            responseBuilder.setBaseRes(baseResBuilder);
            return responseBuilder.build();
        }
        ShardingClient storeShardingClient = storeServerShardings.get(storeShardingIndex);
        StoreMessage.GetRequest storeRequest = StoreMessage.GetRequest.newBuilder()
                .setKey(request.getKey()).build();
        StoreMessage.GetResponse storeResponse = storeShardingClient.getStoreAPI().get(storeRequest);
        if (storeResponse == null
                || storeResponse.getBaseRes().getResCode()
                != CommonMessage.ResCode.RES_CODE_SUCCESS) {
            LOG.warn("store server get request failed, request={}", ProxyUtils.protoToJson(storeRequest));
            responseBuilder.setBaseRes(baseResBuilder);
            return responseBuilder.build();
        }

        responseBuilder.setBaseRes(storeResponse.getBaseRes());
        responseBuilder.setValue(storeResponse.getValue());
        ProxyMessage.GetResponse response = responseBuilder.build();
        LOG.info("get request, request={}, response={}",
                ProxyUtils.protoToJson(request),
                ProxyUtils.protoToJson(response));
        return response;
    }

    @Override
    public ProxyMessage.DeleteResponse delete(ProxyMessage.DeleteRequest request) {
        ProxyMessage.DeleteResponse.Builder responseBuilder = ProxyMessage.DeleteResponse.newBuilder();
        CommonMessage.BaseResponse.Builder baseResBuilder = CommonMessage.BaseResponse.newBuilder();
        baseResBuilder.setResCode(CommonMessage.ResCode.RES_CODE_FAIL);

        // 请求meta server，获取keySign -> sharding映射信息
        GlobalBean globalBean = GlobalBean.getInstance();
        List<ShardingClient> metaServerShardings = globalBean.getMetaServerShadings();
        long keySign = ProxyUtils.getMd5Sign(request.getKey().toByteArray());
        int metaShardingId = (int) (keySign % metaServerShardings.size());
        ShardingClient metaShardingClient = metaServerShardings.get(metaShardingId);
        MetaMessage.GetRequest metaRequest = MetaMessage.GetRequest.newBuilder()
                .setKeySign(keySign).build();
        MetaMessage.GetResponse metaResponse = metaShardingClient.getMetaAPI().get(metaRequest);
        if (metaResponse == null
                || metaResponse.getBaseRes().getResCode()
                != CommonMessage.ResCode.RES_CODE_SUCCESS) {
            LOG.warn("meta server get request failed, request={}", ProxyUtils.protoToJson(metaRequest));
            responseBuilder.setBaseRes(baseResBuilder);
            return responseBuilder.build();
        }
        int storeShardingIndex = metaResponse.getShardingIndex();

        // 请求store server，删除key
        List<ShardingClient> storeServerShardings = globalBean.getStoreServerShardings();
        if (storeShardingIndex >= storeServerShardings.size()) {
            LOG.warn("storeShardingIndex={} >= storeServerShardingSize={}",
                    storeShardingIndex, storeServerShardings.size());
            responseBuilder.setBaseRes(baseResBuilder);
            return responseBuilder.build();
        }
        ShardingClient storeShardingClient = storeServerShardings.get(storeShardingIndex);
        StoreMessage.DeleteRequest storeRequest = StoreMessage.DeleteRequest.newBuilder()
                .setKey(request.getKey()).build();
        StoreMessage.DeleteResponse storeResponse = storeShardingClient.getStoreAPI().delete(storeRequest);
        if (storeResponse == null
                || storeResponse.getBaseRes().getResCode()
                != CommonMessage.ResCode.RES_CODE_SUCCESS) {
            LOG.warn("store server delete request failed, request={}", ProxyUtils.protoToJson(storeRequest));
            responseBuilder.setBaseRes(baseResBuilder);
            return responseBuilder.build();
        }

        // 请求meta server，删除keySign -> sharding映射信息
        MetaMessage.DeleteRequest metaDeleteRequest = MetaMessage.DeleteRequest.newBuilder()
                .setKeySign(keySign).build();
        MetaMessage.DeleteResponse metaDeleteResponse
                = metaShardingClient.getMetaAPI().delete(metaDeleteRequest);
        if (metaDeleteResponse == null
                || metaDeleteResponse.getBaseRes().getResCode()
                != CommonMessage.ResCode.RES_CODE_SUCCESS) {
            LOG.error("meta server delete request failed, request={}", ProxyUtils.protoToJson(metaDeleteRequest));
            responseBuilder.setBaseRes(baseResBuilder);
            return responseBuilder.build();
        }

        responseBuilder.setBaseRes(storeResponse.getBaseRes());
        ProxyMessage.DeleteResponse response = responseBuilder.build();
        LOG.info("get request, request={}, response={}",
                ProxyUtils.protoToJson(request),
                ProxyUtils.protoToJson(response));
        return response;
    }

}
