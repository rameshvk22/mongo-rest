package com.github.mongorest.service;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;

import com.github.mongorest.exception.AuthenticationException;
import com.github.mongorest.exception.AuthorizationException;
import com.github.mongorest.security.Credentials;
import com.github.mongorest.util.Configuration;
import com.github.mongorest.util.HttpStatusMapper;
import com.github.mongorest.util.Utils;
import com.github.mongorest.util.HttpStatusMapper.ClientError;
import com.github.mongorest.util.HttpStatusMapper.ServerError;
import com.github.mongorest.util.HttpStatusMapper.Successful;
import com.github.mongorest.util.Utils.StringUtils;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;
import com.mongodb.MongoException.DuplicateKey;
import com.mongodb.WriteConcern;
import com.mongodb.gridfs.GridFS;
import com.mongodb.gridfs.GridFSInputFile;
import com.mongodb.util.JSON;
import com.sun.jersey.multipart.FormDataBodyPart;
import com.sun.jersey.multipart.FormDataMultiPart;
import com.sun.jersey.spi.resource.Singleton;

@Singleton
@Path("/api/mongo")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MongoRestServiceImpl implements MongoRestService {
    private Logger logger = LoggerFactory.getLogger(MongoRestServiceImpl.class);
    private static final String STATS_DB = "stats";
    private static final String STATS_USER_COLLECTION = "byUser";
    private static final String STATS_USER_KEY = "user";
    private static final String STATS_OP_KEY = "op";
    private static final String STATS_COUNT_KEY = "count";
    private static final String SYS_INDEXES_COLLECTION = "system.indexes";
    private static final String FILTERED_INDEX = "_id_";
    private volatile boolean shutdown;
    private Configuration configuration;
    private Mongo mongo;
    private GridFS gridFs;
    private DBCollection statsByUser;

    @POST
    @Path("/databases")
    @Override
    public Response createDatabase(com.github.mongorest.to.request.Database database, @Context HttpHeaders headers,
            @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbName = constructDbNamespace(credentials.getUserName(), database.getName());
            if (mongo.getDatabaseNames().contains(dbName)) {
                return Response.status(HttpStatusMapper.ClientError.ALREADY_EXISTS.code()).build();
            }

            DB db = mongo.getDB(dbName);
            authServiceAgainstMongo(db);
            if (database.getWriteConcern() != null) {
                db.setWriteConcern(database.getWriteConcern().getMongoWriteConcern());
            } else {
                db.setWriteConcern(WriteConcern.FSYNC_SAFE);
            }
            DBCollection localStats = db.getCollection(STATS_USER_COLLECTION);
            DBObject statsIndex = new BasicDBObject();
            statsIndex.put(STATS_OP_KEY, 1);
            localStats.ensureIndex(statsIndex, null, true);

            URI statusSubResource = uriInfo.getBaseUriBuilder().path(MongoRestServiceImpl.class)
                    .path("/databases/" + database.getName()).build();
            response = Response.created(statusSubResource).build();
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "createDatabase");
        }

        return response;
    }

    @PUT
    @Path("/databases/{dbName}")
    @Override
    public Response updateDatabase(com.github.mongorest.to.request.Database database,
            @PathParam("dbName") String dbName, @Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            boolean created = true;
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                created = false;
            }

            DB db = mongo.getDB(dbNamespace);
            authServiceAgainstMongo(db);
            if (database.getWriteConcern() != null) {
                db.setWriteConcern(database.getWriteConcern().getMongoWriteConcern());
            }

            URI statusSubResource = uriInfo.getBaseUriBuilder().path(MongoRestServiceImpl.class)
                    .path("/databases/" + dbName).build();
            if (created) {
                DBCollection localStats = db.getCollection(STATS_USER_COLLECTION);
                DBObject statsIndex = new BasicDBObject();
                statsIndex.put(STATS_OP_KEY, 1);
                localStats.ensureIndex(statsIndex, null, true);
                response = Response.created(statusSubResource).build();
            } else {
                response = Response.ok(statusSubResource).build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "updateDatabase");
        }

        return response;
    }

    @DELETE
    @Path("/databases/{dbName}")
    @Override
    public Response deleteDatabase(@PathParam("dbName") String dbName, @Context HttpHeaders headers,
            @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                mongo.dropDatabase(dbNamespace);
                response = Response.ok().build();
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "deleteDatabase");
        }

        return response;
    }

    @GET
    @Path("/databases/{dbName}")
    @Override
    public Response findDatabase(@PathParam("dbName") String dbName,
            @QueryParam("collDetails") @DefaultValue("false") boolean collDetails, @Context HttpHeaders headers,
            @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                com.github.mongorest.to.response.Database database = searchDatabase(dbName, dbNamespace, collDetails);
                response = Response.ok(database).build();
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "findDatabase");
        }

        return response;
    }

    @GET
    @Path("/databases")
    @Override
    public Response findDatabases(@QueryParam("collDetails") @DefaultValue("false") boolean collDetails,
            @Context HttpHeaders headers, @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            List<com.github.mongorest.to.response.Database> databases = new ArrayList<com.github.mongorest.to.response.Database>();
            String dbNamePrefix = "dbs:" + credentials.getUserName();
            for (String dbName : mongo.getDatabaseNames()) {
                if (dbName.startsWith(dbNamePrefix)) {
                    com.github.mongorest.to.response.Database database = searchDatabase(
                            dbName.substring(dbNamePrefix.length() + 1), dbName, collDetails);
                    databases.add(database);
                }
            }
            response = Response.ok(databases).build();
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "findDatabases");
        }

        return response;
    }

    @DELETE
    @Path("/databases")
    @Override
    public Response deleteDatabases(@Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            List<String> databases = new ArrayList<String>();
            String dbNamePrefix = "dbs:" + credentials.getUserName();
            for (String dbName : mongo.getDatabaseNames()) {
                if (dbName.startsWith(dbNamePrefix)) {
                    mongo.dropDatabase(dbName);
                    databases.add(dbName.substring(dbNamePrefix.length() + 1));
                }
            }
            response = Response.ok("Deleted databases: " + databases).build();
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "deleteDatabases");
        }

        return response;
    }

    @POST
    @Path("/databases/{dbName}/collections")
    @Override
    public Response createCollection(@PathParam("dbName") String dbName,
            com.github.mongorest.to.request.Collection collection, @Context HttpHeaders headers,
            @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                DBObject options = new BasicDBObject();
                options.put("max", configuration.getMaxDocsPerCollection());
                DBCollection dbCollection = db.createCollection(collection.getName(), options);
                if (collection.getWriteConcern() != null) {
                    dbCollection.setWriteConcern(collection.getWriteConcern().getMongoWriteConcern());
                }

                URI statusSubResource = uriInfo.getBaseUriBuilder().path(MongoRestServiceImpl.class)
                        .path("/databases/" + dbName + "/collections/" + collection.getName()).build();
                response = Response.created(statusSubResource).build();
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "createCollection");
        }

        return response;
    }

    @GET
    @Path("/databases/{dbName}/collections/{collName}")
    @Override
    public Response findCollection(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            @Context HttpHeaders headers, @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                if (db.getCollectionNames().contains(collName)) {
                    com.github.mongorest.to.response.Collection collection = searchCollection(collName, dbName, db);
                    response = Response.ok(collection).build();
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "findCollection");
        }

        return response;
    }

    @PUT
    @Path("/databases/{dbName}/collections/{collName}")
    @Override
    public Response updateCollection(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            com.github.mongorest.to.request.Collection collection, @Context HttpHeaders headers,
            @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();

            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            DB db = mongo.getDB(dbNamespace);
            authServiceAgainstMongo(db);
            DBCollection dbCollection = null;
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                URI statusSubResource = uriInfo.getBaseUriBuilder().path(MongoRestServiceImpl.class)
                        .path("/databases/" + dbName + "/collections/" + collection.getName()).build();
                if (db.getCollectionNames().contains(collection.getName())) {
                    dbCollection = db.getCollection(collection.getName());
                    if (collection.getWriteConcern() != null) {
                        dbCollection.setWriteConcern(collection.getWriteConcern().getMongoWriteConcern());
                    }
                    response = Response.ok(statusSubResource).build();
                } else {
                    DBObject options = new BasicDBObject();
                    options.put("max", configuration.getMaxDocsPerCollection());
                    dbCollection = db.createCollection(collection.getName(), options);
                    if (collection.getWriteConcern() != null) {
                        dbCollection.setWriteConcern(collection.getWriteConcern().getMongoWriteConcern());
                    }
                    response = Response.created(statusSubResource).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "updateCollection");
        }

        return response;
    }

    @DELETE
    @Path("/databases/{dbName}/collections/{collName}")
    @Override
    public Response deleteCollection(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            @Context HttpHeaders headers, @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                if (db.getCollectionNames().contains(collName)) {
                    DBCollection collection = db.getCollection(collName);
                    collection.dropIndexes();
                    collection.drop();
                    response = Response.ok().build();
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "deleteCollection");
        }

        return response;
    }

    @GET
    @Path("/databases/{dbName}/collections")
    @Override
    public Response findCollections(@PathParam("dbName") String dbName, @Context HttpHeaders headers,
            @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                List<com.github.mongorest.to.response.Collection> collections = new ArrayList<com.github.mongorest.to.response.Collection>();
                for (String collName : db.getCollectionNames()) {
                    if (SYS_INDEXES_COLLECTION.equals(collName) || STATS_USER_COLLECTION.equals(collName)) {
                        continue;
                    }
                    com.github.mongorest.to.response.Collection collection = searchCollection(collName, dbName, db);
                    collections.add(collection);
                }
                response = Response.ok(collections).build();
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "findCollections");
        }

        return response;
    }

    @DELETE
    @Path("/databases/{dbName}/collections")
    @Override
    public Response deleteCollections(@PathParam("dbName") String dbName, @Context HttpHeaders headers,
            @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                List<String> collections = new ArrayList<String>();
                for (String collName : db.getCollectionNames()) {
                    if (collName.equals(SYS_INDEXES_COLLECTION)) {
                        continue;
                    }
                    collections.add(collName);
                    DBCollection collection = db.getCollection(collName);
                    collection.dropIndexes();
                    collection.drop();
                }
                response = Response.ok("Deleted collections: " + collections).build();
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "deleteCollections");
        }

        return response;
    }

    @POST
    @Path("/databases/{dbName}/collections/{collName}/indexes")
    @Override
    public Response createIndex(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            com.github.mongorest.to.request.Index index, @Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();

            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                if (db.getCollectionNames().contains(collName)) {
                    DBCollection dbCollection = db.getCollection(collName);
                    List<String> keys = index.getKeys();
                    if (keys != null && !keys.isEmpty()) {
                        DBObject keysObject = new BasicDBObject();
                        for (String key : keys) {
                            if (!StringUtils.isNullOrEmpty(key)) {
                                keysObject.put(key, 1);
                            }
                        }
                        dbCollection.ensureIndex(keysObject, index.getName(), index.isUnique());
                        URI statusSubResource = uriInfo
                                .getBaseUriBuilder()
                                .path(MongoRestServiceImpl.class)
                                .path("/databases/" + dbName + "/collections/" + collName + "/indexes/"
                                        + index.getName()).build();
                        response = Response.created(statusSubResource).build();
                    } else {
                        response = Response.status(ClientError.BAD_REQUEST.code())
                                .entity("Cannot create an index with unspecified keys").build();
                    }
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "createIndex");
        }

        return response;
    }

    @SuppressWarnings("unchecked")
    @GET
    @Path("/databases/{dbName}/collections/{collName}/indexes/{indexName}")
    @Override
    public Response findIndex(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            @PathParam("indexName") String indexName, @Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                if (db.getCollectionNames().contains(collName)) {
                    DBCollection dbCollection = db.getCollection(collName);
                    List<DBObject> indexInfos = dbCollection.getIndexInfo();
                    boolean indexFound = false;
                    com.github.mongorest.to.response.Index foundIndex = new com.github.mongorest.to.response.Index();
                    for (DBObject indexInfo : indexInfos) {
                        String foundIndexName = (String) indexInfo.get("name");
                        if (foundIndexName.equals(indexName)) {
                            Map<String, Object> keys = (Map<String, Object>) indexInfo.get("key");
                            foundIndex.setName(indexName);
                            foundIndex.setCollectionName(collName);
                            foundIndex.setDbName(dbName);
                            foundIndex.setKeys(keys.keySet());
                            foundIndex.setUnique((Boolean) indexInfo.get("unique"));
                            indexFound = true;
                            break;
                        }
                    }
                    if (indexFound) {
                        response = Response.ok(foundIndex).build();
                    } else {
                        response = Response.status(ClientError.NOT_FOUND.code())
                                .entity(indexName + " does not exist for " + collName).build();
                    }
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "findIndex");
        }

        return response;
    }

    @DELETE
    @Path("/databases/{dbName}/collections/{collName}/indexes/{indexName}")
    @Override
    public Response deleteIndex(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            @PathParam("indexName") String indexName, @Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                if (db.getCollectionNames().contains(collName)) {
                    DBCollection dbCollection = db.getCollection(collName);
                    List<DBObject> indexInfos = dbCollection.getIndexInfo();
                    boolean indexFound = false;
                    for (DBObject indexInfo : indexInfos) {
                        String foundIndexName = (String) indexInfo.get("name");
                        if (foundIndexName.equals(indexName)) {
                            indexFound = true;
                            dbCollection.dropIndex(indexName);
                            break;
                        }
                    }
                    if (indexFound) {
                        response = Response.ok().build();
                    } else {
                        response = Response.status(ClientError.NOT_FOUND.code())
                                .entity(indexName + " does not exist for " + collName).build();
                    }
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "deleteIndex");
        }

        return response;
    }

    @SuppressWarnings("unchecked")
    @GET
    @Path("/databases/{dbName}/collections/{collName}/indexes")
    @Override
    public Response findIndexes(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            @Context HttpHeaders headers, @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                if (db.getCollectionNames().contains(collName)) {
                    DBCollection dbCollection = db.getCollection(collName);
                    List<com.github.mongorest.to.response.Index> indexes = new ArrayList<com.github.mongorest.to.response.Index>();
                    for (DBObject indexInfo : dbCollection.getIndexInfo()) {
                        String indexName = (String) indexInfo.get("name");
                        if (FILTERED_INDEX.equals(indexName)) {
                            continue;
                        }
                        com.github.mongorest.to.response.Index index = new com.github.mongorest.to.response.Index();
                        index.setName(indexName);
                        index.setCollectionName(collName);
                        index.setDbName(dbName);
                        index.setUnique((Boolean) indexInfo.get("unique"));
                        Map<String, Object> keys = (Map<String, Object>) indexInfo.get("key");
                        index.setKeys(keys.keySet());
                        indexes.add(index);
                    }
                    response = Response.ok(indexes).build();
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "findIndexes");
        }

        return response;
    }

    @DELETE
    @Path("/databases/{dbName}/collections/{collName}/indexes")
    @Override
    public Response deleteIndexes(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            @Context HttpHeaders headers, @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                if (db.getCollectionNames().contains(collName)) {
                    DBCollection dbCollection = db.getCollection(collName);
                    List<DBObject> indexInfos = dbCollection.getIndexInfo();
                    List<String> indexNames = new ArrayList<String>();
                    for (DBObject indexInfo : indexInfos) {
                        String indexName = (String) indexInfo.get("name");
                        if (FILTERED_INDEX.equals(indexName)) {
                            continue;
                        }
                        indexNames.add(indexName);
                        dbCollection.dropIndex(indexName);
                    }
                    response = Response.ok("Deleted indexes: " + indexNames).build();
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "deleteIndexes");
        }

        return response;
    }

    @POST
    @Path("/databases/{dbName}/collections/{collName}/documents")
    @Override
    public Response createDocument(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            com.github.mongorest.to.request.Document document, @Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                if (db.getCollectionNames().contains(collName)) {
                    DBCollection dbCollection = db.getCollection(collName);
                    String documentJson = document.getJson();
                    if (!StringUtils.isNullOrEmpty(documentJson)) {
                        DBObject mongoDocument = (DBObject) JSON.parse(document.getJson());
                        try {
                            dbCollection.insert(mongoDocument, WriteConcern.SAFE);
                            ObjectId documentId = ((ObjectId) mongoDocument.get("_id"));
                            if (documentId != null && !StringUtils.isNullOrEmpty(documentId.toString())) {
                                URI statusSubResource = uriInfo
                                        .getBaseUriBuilder()
                                        .path(MongoRestServiceImpl.class)
                                        .path("/databases/" + dbName + "/collections/" + collName + "/documents/"
                                                + documentId).build();
                                response = Response.created(statusSubResource).build();
                            } else {
                                response = Response.status(ServerError.RUNTIME_ERROR.code())
                                        .entity(ServerError.RUNTIME_ERROR.message()).build();
                            }
                        } catch (DuplicateKey duplicateObject) {
                            response = Response.status(ClientError.BAD_REQUEST.code())
                                    .entity("Document already exists and could not be created").build();
                        }
                    } else {
                        response = Response.status(ClientError.BAD_REQUEST.code()).entity("Document JSON is required")
                                .build();
                    }
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "createDocument");
        }
        return response;
    }

    @GET
    @Path("/databases/{dbName}/collections/{collName}/documents/{docId}")
    @Override
    public Response findDocument(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            @PathParam("docId") String docId, @Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                if (db.getCollectionNames().contains(collName)) {
                    DBCollection dbCollection = db.getCollection(collName);
                    DBObject query = new BasicDBObject();
                    query.put("_id", new ObjectId(docId));
                    DBObject found = dbCollection.findOne(query);
                    if (found != null) {
                        com.github.mongorest.to.response.Document document = new com.github.mongorest.to.response.Document();
                        document.setJson(JSON.serialize(found));
                        response = Response.ok(document).build();
                    } else {
                        response = Response.status(ClientError.NOT_FOUND.code())
                                .entity(docId + " does not exist in " + collName).build();
                    }
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "findDocument");
        }
        return response;
    }

    @PUT
    @Path("/databases/{dbName}/collections/{collName}/documents/{docId}")
    @Override
    public Response updateDocument(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            @PathParam("docId") String docId, com.github.mongorest.to.request.Document document,
            @Context HttpHeaders headers, @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                if (db.getCollectionNames().contains(collName)) {
                    DBCollection dbCollection = db.getCollection(collName);
                    String documentJson = document.getJson();
                    if (!StringUtils.isNullOrEmpty(documentJson)) {
                        DBObject incomingDocument = (DBObject) JSON.parse(documentJson);
                        DBObject query = new BasicDBObject();
                        query.put("_id", new ObjectId(docId));
                        DBObject persistedDocument = dbCollection.findOne(query);
                        URI statusSubResource = null;
                        try {
                            if (persistedDocument == null) {
                                dbCollection.insert(incomingDocument, WriteConcern.SAFE);
                                statusSubResource = uriInfo
                                        .getBaseUriBuilder()
                                        .path(MongoRestServiceImpl.class)
                                        .path("/databases/" + dbName + "/collections/" + collName + "/documents/"
                                                + ((DBObject) incomingDocument.get("_id"))).build();
                                response = Response.created(statusSubResource).build();
                            } else {
                                dbCollection.save(incomingDocument);
                                statusSubResource = uriInfo
                                        .getBaseUriBuilder()
                                        .path(MongoRestServiceImpl.class)
                                        .path("/databases/" + dbName + "/collections/" + collName + "/documents/"
                                                + docId).build();
                                response = Response.ok(statusSubResource).build();
                            }
                        } catch (DuplicateKey duplicateObject) {
                            response = Response.status(ClientError.BAD_REQUEST.code())
                                    .entity("Document already exists and could not be created").build();
                        }
                    } else {
                        response = Response.status(ClientError.BAD_REQUEST.code()).entity("Document JSON is required")
                                .build();
                    }
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "updateDocument");
        }
        return response;
    }

    @DELETE
    @Path("/databases/{dbName}/collections/{collName}/documents/{docId}")
    @Override
    public Response deleteDocument(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            @PathParam("docId") String docId, @Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                if (db.getCollectionNames().contains(collName)) {
                    DBCollection dbCollection = db.getCollection(collName);
                    if (!StringUtils.isNullOrEmpty(docId)) {
                        DBObject query = new BasicDBObject();
                        query.put("_id", new ObjectId(docId));
                        dbCollection.remove(query);
                        response = Response.ok().build();
                    }
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "deleteDocument");
        }
        return response;
    }

    @GET
    @Path("/databases/{dbName}/collections/{collName}/documents")
    @Override
    public Response findDocuments(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            @Context HttpHeaders headers, @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                List<com.github.mongorest.to.response.Document> documents = new ArrayList<com.github.mongorest.to.response.Document>();
                if (db.getCollectionNames().contains(collName)) {
                    DBCollection dbCollection = db.getCollection(collName);
                    DBCursor cursor = dbCollection.find();
                    while (cursor.hasNext()) {
                        DBObject found = cursor.next();
                        if (found != null) {
                            com.github.mongorest.to.response.Document document = new com.github.mongorest.to.response.Document();
                            document.setJson(JSON.serialize(found));
                            documents.add(document);
                        }
                    }
                    response = Response.ok(documents).build();
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "findDocuments");
        }
        return response;
    }

    @DELETE
    @Path("/databases/{dbName}/collections/{collName}/documents")
    @Override
    public Response deleteDocuments(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            @Context HttpHeaders headers, @Context UriInfo uriInfo, @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            String dbNamespace = constructDbNamespace(credentials.getUserName(), dbName);
            if (mongo.getDatabaseNames().contains(dbNamespace)) {
                DB db = mongo.getDB(dbNamespace);
                authServiceAgainstMongo(db);
                if (db.getCollectionNames().contains(collName)) {
                    DBCollection dbCollection = db.getCollection(collName);
                    DBCursor cursor = dbCollection.find();
                    while (cursor.hasNext()) {
                        DBObject found = cursor.next();
                        if (found != null) {
                            dbCollection.remove(found);
                        }
                    }
                    response = Response.ok().build();
                } else {
                    response = Response.status(ClientError.NOT_FOUND.code())
                            .entity(collName + " does not exist in " + dbName).build();
                }
            } else {
                response = Response.status(ClientError.NOT_FOUND.code()).entity(dbName + " does not exist").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "deleteDocuments");
        }
        return response;
    }

    @GET
    @Path("/ping")
    @Override
    public Response ping(@Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context SecurityContext securityContext) {
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            boolean alive = !shutdown && mongo.getConnector().isOpen();
            if (alive) {
                updateStats(user, "ping");
            }
            response = alive ? Response.ok().entity(Successful.SERVICE_ALIVE.message()).build() : Response
                    .status(ServerError.SERVICE_UNAVAILABLE.code()).entity(ServerError.SERVICE_UNAVAILABLE.message())
                    .build();
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        }
        return response;
    }

    @GET
    @Path("/shutdown")
    @Override
    public Response shutdown(@Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context SecurityContext securityContext) {
        // wire shiro to allow only privileged users to shutdown service
        Response response = null;
        try {
            if (!shutdown) {
                shutdown = true;
                if (mongo.getConnector().isOpen()) {
                    updateStats(authenticateAndAuthorize(headers, uriInfo, securityContext).getUserName(), "shutdown");
                    mongo.close();
                    response = Response.ok().build();
                } else {
                    response = Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                            .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
                }
            } else {
                response = Response.notModified("Service is already shutdown.").build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        }
        return response;
    }

    @POST
    @Consumes("multipart/form-data")
    @Path("/databases/{dbName}/collections/{collName}/binary")
    @Override
    public Response createBinaryDocument(@PathParam("dbName") String dbName, @PathParam("collName") String collName,
            FormDataMultiPart document, @Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context SecurityContext securityContext) {
        if (shutdown) {
            return Response.status(ServerError.SERVICE_UNAVAILABLE.code())
                    .entity(ServerError.SERVICE_UNAVAILABLE.message()).build();
        }
        Response response = null;
        String user = null;
        try {
            Credentials credentials = authenticateAndAuthorize(headers, uriInfo, securityContext);
            user = credentials.getUserName();
            FormDataBodyPart file = document.getField("file");
            String fileName = file.getContentDisposition().getFileName();
            InputStream fileStream = file.getEntityAs(InputStream.class);
            GridFSInputFile gridfsFile = gridFs.createFile(fileStream);
            gridfsFile.setFilename(fileName);
            gridfsFile.save();
            ObjectId documentId = (ObjectId) gridfsFile.getId();
            if (documentId != null && !StringUtils.isNullOrEmpty(documentId.toString())) {
                URI statusSubResource = uriInfo.getBaseUriBuilder().path(MongoRestServiceImpl.class)
                        .path("/databases/" + dbName + "/collections/" + collName + "/binary/" + documentId).build();
                response = Response.created(statusSubResource).build();
            } else {
                response = Response.status(ServerError.RUNTIME_ERROR.code())
                        .entity(ServerError.RUNTIME_ERROR.message()).build();
            }
        } catch (Exception exception) {
            response = lobException(exception, headers, uriInfo);
        } finally {
            updateStats(user, "createBinaryDocument");
        }
        return response;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private com.github.mongorest.to.response.Database searchDatabase(String dbName, String dbNamespace,
            boolean collDetails) {
        DB db = mongo.getDB(dbNamespace);
        authServiceAgainstMongo(db);
        com.github.mongorest.to.response.Database database = new com.github.mongorest.to.response.Database();
        database.setName(dbName);
        database.setWriteConcern(com.github.mongorest.to.response.WriteConcern.fromMongoWriteConcern(db
                .getWriteConcern()));
        Map statsMap = db.getStats().toMap();
        statsMap.remove("db");
        database.setStats(Utils.CollectionUtils.stringifyMapEntries(statsMap));
        if (collDetails) {
            List<com.github.mongorest.to.response.Collection> collections = new ArrayList<com.github.mongorest.to.response.Collection>();
            for (String collName : db.getCollectionNames()) {
                if (collName.equals(SYS_INDEXES_COLLECTION) || collName.equals(STATS_USER_COLLECTION)) {
                    continue;
                }
                com.github.mongorest.to.response.Collection collection = searchCollection(collName, dbName, db);
                collections.add(collection);
            }
            database.setCollections(collections);
        }
        return database;
    }

    @SuppressWarnings("unchecked")
    private com.github.mongorest.to.response.Collection searchCollection(String collName, String dbName, DB db) {
        DBCollection dbCollection = db.getCollection(collName);
        com.github.mongorest.to.response.Collection collection = new com.github.mongorest.to.response.Collection();
        collection.setDbName(dbName);
        collection.setName(collName);
        collection.setWriteConcern(com.github.mongorest.to.response.WriteConcern.fromMongoWriteConcern(dbCollection
                .getWriteConcern()));
        List<DBObject> indexInfos = dbCollection.getIndexInfo();
        List<com.github.mongorest.to.response.Index> indexes = new ArrayList<com.github.mongorest.to.response.Index>();
        for (DBObject indexInfo : indexInfos) {
            Map<String, Object> indexed = (Map<String, Object>) indexInfo.get("key");
            if (indexed != null) {
                com.github.mongorest.to.response.Index index = new com.github.mongorest.to.response.Index();
                index.setDbName(dbName);
                index.setCollectionName(collName);
                index.setKeys(indexed.keySet());
                indexes.add(index);
            }
        }
        collection.setIndexes(indexes);
        List<com.github.mongorest.to.response.Document> documents = new ArrayList<com.github.mongorest.to.response.Document>();
        DBCursor cursor = dbCollection.find();
        while (cursor.hasNext()) {
            com.github.mongorest.to.response.Document document = new com.github.mongorest.to.response.Document();
            DBObject dbDoc = cursor.next();
            document.setJson(dbDoc.toString());
            documents.add(document);
        }
        collection.setDocuments(documents);
        return collection;
    }

    /**
     * 1. Authenticate and Authorize (SAC) via SecurityService<br>
     * 2. Maintain cached Lease/Client on Server-side
     **/
    private Credentials authenticateAndAuthorize(HttpHeaders headers, UriInfo uriInfo, SecurityContext securityContext)
            throws AuthenticationException, AuthorizationException {
        if (logger.isDebugEnabled()) {
            logRequestContext(headers, uriInfo);
        }
        String username = null, password = null;
        List<String> requestHeader = headers.getRequestHeader("authorization");
        try {
            for (String authHeader : requestHeader) {
                String[] pieces = authHeader.split(" ");
                if ("basic".equalsIgnoreCase(pieces[0].trim())) {
                    String[] userPasswd = Utils.EncodingUtils.decodeBase64(pieces[1].trim()).split(":");
                    username = userPasswd[0].trim().toLowerCase();
                    password = userPasswd[1].trim();
                    break;
                }
            }
        } catch (Exception exception) {
            throw new AuthenticationException(
                    "Mongo Data Service expects Base64 encoded Authorization header containing username and password",
                    exception);
        }
        if (Utils.StringUtils.isNullOrEmpty(username) || Utils.StringUtils.isNullOrEmpty(password)) {
            throw new AuthenticationException(
                    "Mongo Data Service expects Base64 encoded Authorization header containing username and password");
        }

        String userIP = null;
        requestHeader = headers.getRequestHeader("x-real-ip");
        if (requestHeader != null && !requestHeader.isEmpty()) {
            userIP = requestHeader.get(0);
        }

        String userAgent = null;
        requestHeader = headers.getRequestHeader("user-agent");
        if (requestHeader != null && !requestHeader.isEmpty()) {
            userAgent = requestHeader.get(0);
        }

        return new Credentials(null, null, username, password, userIP, userAgent, uriInfo.getRequestUri().toString());
    }

    private void authServiceAgainstMongo(final DB db) throws MongoException {
        if (!db.isAuthenticated() && !StringUtils.isNullOrEmpty(configuration.getDataStoreUsername())
                && !StringUtils.isNullOrEmpty(configuration.getDataStorePassword())) {
            db.authenticate(configuration.getDataStoreUsername(), configuration.getDataStorePassword().toCharArray());
        }
    }

    private void logError(String message, Throwable exception, HttpHeaders headers, UriInfo uriInfo) {
        logRequestContext(headers, uriInfo);
        logger.error(message, exception);
    }

    private void logRequestContext(HttpHeaders headers, UriInfo uriInfo) {
        MultivaluedMap<String, String> headerParams = headers.getRequestHeaders();
        StringBuffer buffer = new StringBuffer("Headers=");
        for (Entry<String, List<String>> header : headerParams.entrySet()) {
            buffer.append(header.getKey()).append("=").append(header.getValue()).append(" ");
        }
        buffer.append("uri=").append(uriInfo.getRequestUri());
        logger.debug(buffer.toString());
    }

    private Response lobException(Exception exception, HttpHeaders headers, UriInfo uriInfo) {
        Response response = null;
        if (exception instanceof AuthenticationException || exception instanceof AuthorizationException) {
            logError("Service failure: user-auth", exception, headers, uriInfo);
            response = Response.status(ClientError.UNAUTHORIZED.code()).entity(ClientError.UNAUTHORIZED.message())
                    .build();
        } else if (exception instanceof MongoException) {
            logError("Service failure: mongo-persistence", exception, headers, uriInfo);
            response = Response.status(ServerError.RUNTIME_ERROR.code()).entity(ServerError.RUNTIME_ERROR.message())
                    .build();
        } else {
            logError("Service failure: see-stacktrace", exception, headers, uriInfo);
            response = Response.status(ServerError.RUNTIME_ERROR.code()).entity(ServerError.RUNTIME_ERROR.message())
                    .build();
        }
        return response;
    }

    private String constructDbNamespace(String userName, String dbName) {
        return "dbs:" + userName + ":" + dbName;
    }

    private void updateStats(String user, String op) {
        if (user != null) {
            BasicDBObject query = new BasicDBObject();
            query.put(STATS_USER_KEY, user);
            query.put(STATS_OP_KEY, op);
            BasicDBObject update = new BasicDBObject();
            update.put("$inc", new BasicDBObject(STATS_COUNT_KEY, 1));
            statsByUser.findAndModify(query, null, update);
        }
    }

    public void init() {
        DB statsDb = mongo.getDB(STATS_DB);
        statsByUser = statsDb.getCollection(STATS_USER_COLLECTION);
        DBObject statsIndex = new BasicDBObject();
        statsIndex.put(STATS_USER_KEY, 1);
        statsByUser.ensureIndex(statsIndex, null, true);
    }

    @Required
    public void setMongo(Mongo mongo) {
        this.mongo = mongo;
    }

    @Required
    public void setConfiguration(Configuration configuration) {
        this.configuration = configuration;
        // BasicConfigurator.configure(); Testing only
    }
}
