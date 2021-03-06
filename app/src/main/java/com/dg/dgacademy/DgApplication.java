package com.dg.dgacademy;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import com.apollographql.apollo.ApolloCall;
import com.apollographql.apollo.ApolloClient;
import com.apollographql.apollo.CustomTypeAdapter;
import com.apollographql.apollo.api.Operation;
import com.apollographql.apollo.api.Query;
import com.apollographql.apollo.api.Response;
import com.apollographql.apollo.api.ResponseField;
import com.apollographql.apollo.api.cache.http.HttpCachePolicy;
import com.apollographql.apollo.cache.normalized.CacheKey;
import com.apollographql.apollo.cache.normalized.CacheKeyResolver;
import com.apollographql.apollo.cache.normalized.NormalizedCacheFactory;
import com.apollographql.apollo.cache.normalized.lru.EvictionPolicy;
import com.apollographql.apollo.cache.normalized.lru.LruNormalizedCacheFactory;
import com.apollographql.apollo.cache.normalized.sql.ApolloSqlHelper;
import com.apollographql.apollo.cache.normalized.sql.SqlNormalizedCacheFactory;
import com.apollographql.apollo.exception.ApolloException;
import com.apollographql.apollo.fetcher.ResponseFetcher;
import com.auth0.android.Auth0;
import com.auth0.android.authentication.AuthenticationAPIClient;
import com.auth0.android.authentication.AuthenticationException;
import com.auth0.android.authentication.request.SignUpRequest;
import com.auth0.android.authentication.storage.CredentialsManagerException;
import com.auth0.android.authentication.storage.SecureCredentialsManager;
import com.auth0.android.authentication.storage.SharedPreferencesStorage;
import com.auth0.android.callback.BaseCallback;
import com.auth0.android.request.AuthenticationRequest;
import com.auth0.android.result.Credentials;
import com.dg.dgacademy.activities.LoginActivity;
import com.dg.dgacademy.model.DraftsEvent;
import com.dg.dgacademy.model.GlobalNetworkException;
import com.dg.dgacademy.model.PublicationsEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import api.AdminQuery;
import api.AllPublicDraftsQuery;
import api.AllPublicationsQuery;
import api.AuthenticateMutation;
import api.CreateDraftMutation;
import api.CreateNotificationMutation;
import api.CreatePublicationMutation;
import api.DeleteDraftMutation;
import api.DeleteNotificationMutation;
import api.DeletePublicationMutation;
import api.LikeDraftMutation;
import api.LikePublicationMutation;
import api.UnlikeDraftMutation;
import api.UnlikePublicationMutation;
import api.UpdateDraftMutation;
import api.UpdateUserMutation;
import api.fragment.DraftInfo;
import api.fragment.PublicationInfo;
import api.type.CustomType;
import api.type.DraftType;
import api.type.NotificationType;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;


public class DgApplication extends Application {

    private static AuthenticationAPIClient auth0Client;
    private static ApolloClient apolloClient;
    private static String NETWORK_ERROR;
    private static String GRAPHCOOL_URL;
    private static SecureCredentialsManager tokenStorage;
    private static String USER_ID = "";
    private static Context context;

    @Override
    public void onCreate() {
        super.onCreate();

        EventBus.getDefault().register(this);

        Auth0 auth0 = new Auth0(getString(R.string.com_auth0_client_id), getString(R.string.com_auth0_domain));
        auth0.setOIDCConformant(true);

        context = getApplicationContext();
        NETWORK_ERROR = getString(R.string.network_error);
        GRAPHCOOL_URL = getString(R.string.graphCool_url);
        auth0Client = new AuthenticationAPIClient(auth0);
        tokenStorage = new SecureCredentialsManager(context, auth0Client, new SharedPreferencesStorage(context));

        apolloClient = ApolloClient.builder().serverUrl(GRAPHCOOL_URL).build();
    }

    public static boolean isLoggedIn() {
        return tokenStorage.hasValidCredentials();
    }

    public static void login(String email, String password) {
        AuthenticationRequest request = auth0Client.login(email, password, "academy-db-connection");
        request.setAudience("dg-academy");
        request.setScope("offline_access");

        request.start(new BaseCallback<Credentials, AuthenticationException>() {

            @Override
            public void onSuccess(Credentials payload) {
                tokenStorage.saveCredentials(payload);
                EventBus.getDefault().post(payload);
            }

            @Override
            public void onFailure(AuthenticationException error) {
                EventBus.getDefault().post(new GlobalNetworkException(error.getDescription()));
            }

        });

    }

    public static void signUp(String username, String email, String password) {
        SignUpRequest request = auth0Client.signUp(email, password, username, "academy-db-connection");
        request.start(new BaseCallback<Credentials, AuthenticationException>() {
            @Override
            public void onSuccess(Credentials payload) {
                EventBus.getDefault().post(payload);
            }

            @Override
            public void onFailure(AuthenticationException error) {
                EventBus.getDefault().post(new GlobalNetworkException(error.getDescription()));
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGlobalNetworkException(GlobalNetworkException e) {
        Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshTokenFailure(CredentialsManagerException e) {
        Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, LoginActivity.class));
    }

    public static void requestPublicDrafts(ResponseFetcher fetcher) {
        checkCredentials(() -> {
            AllPublicDraftsQuery query = AllPublicDraftsQuery.builder().build();

            apolloClient.query(query).responseFetcher(fetcher).enqueue(new ApolloCall.Callback<AllPublicDraftsQuery.Data>() {
                @Override
                public void onResponse(@Nonnull Response<AllPublicDraftsQuery.Data> response) {
                    if (response.data().allDrafts() != null) {
                        List<DraftInfo> drafts = response.data().allDrafts()
                                .stream().map(p -> p.fragments().draftInfo())
                                .filter(p -> !Objects.equals(p.owner().fragments().userInfo().id(), USER_ID))
                                .collect(Collectors.toList());
                        EventBus.getDefault().postSticky(new DraftsEvent(drafts));
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });
    }

    public static void requestPublicPublications(ResponseFetcher fetcher) {
        checkCredentials(() -> {
            AllPublicationsQuery query = AllPublicationsQuery.builder().build();

            apolloClient.query(query).responseFetcher(fetcher).enqueue(new ApolloCall.Callback<AllPublicationsQuery.Data>() {
                @Override
                public void onResponse(@Nonnull Response<AllPublicationsQuery.Data> response) {
                    if (response.data().allPublications() != null) {
                        List<PublicationInfo> publications = response.data()
                                .allPublications()
                                .stream()
                                .map(p -> p.fragments().publicationInfo())
                                .filter(p -> !Objects.equals(p.owner().fragments().userInfo().id(), USER_ID))
                                .collect(Collectors.toList());

                        EventBus.getDefault().postSticky(new PublicationsEvent(publications));
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });
    }

    public static void requestPublicDraft(String id, ResponseFetcher fetcher) {
        checkCredentials(() -> {
            AllPublicDraftsQuery query = AllPublicDraftsQuery.builder().build();

            apolloClient.query(query).responseFetcher(fetcher).enqueue(new ApolloCall.Callback<AllPublicDraftsQuery.Data>() {
                @Override
                public void onResponse(@Nonnull Response<AllPublicDraftsQuery.Data> response) {
                    if (response.data().allDrafts() != null) {
                        List<DraftInfo> drafts = response.data().allDrafts()
                                .stream().map(p -> p.fragments().draftInfo()).collect(Collectors.toList());
                        EventBus.getDefault().postSticky(drafts.stream().filter(p -> Objects.equals(p.id(), id)).findFirst().get());
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });
    }


    public static void requestPrivateDraft(String id, ResponseFetcher fetcher) {
        checkCredentials(() -> {
            AdminQuery query = AdminQuery.builder().id(USER_ID).build();

            apolloClient.query(query).responseFetcher(fetcher).enqueue(new ApolloCall.Callback<AdminQuery.Data>() {
                @Override
                public void onResponse(@Nonnull Response<AdminQuery.Data> response) {
                    if (response.data().User() != null) {
                        Optional<DraftInfo> draft = response.data().User().drafts()
                                .stream().map(p -> p.fragments().draftInfo())
                                .filter(p -> Objects.equals(p.id(), id)).findFirst();
                        if (draft.isPresent())
                            EventBus.getDefault().postSticky(draft.get());
                        else
                            EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });
    }


    public static void requestPublication(String id, ResponseFetcher fetcher) {
        checkCredentials(() -> {
            AllPublicationsQuery query = AllPublicationsQuery.builder().build();

            apolloClient.query(query).responseFetcher(fetcher).enqueue(new ApolloCall.Callback<AllPublicationsQuery.Data>() {
                @Override
                public void onResponse(@Nonnull Response<AllPublicationsQuery.Data> response) {
                    if (response.data().allPublications() != null) {
                        Optional<PublicationInfo> publication = response.data().allPublications()
                                .stream().map(p -> p.fragments().publicationInfo())
                                .filter(p -> Objects.equals(p.id(), id)).findFirst();
                        if (publication.isPresent())
                            EventBus.getDefault().postSticky(publication.get());
                        else
                            EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });
    }

    public static void requestPrivateDrafts(ResponseFetcher fetcher) {
        checkCredentials(() -> {
            AdminQuery query = AdminQuery.builder().id(USER_ID).build();

            apolloClient.query(query).responseFetcher(fetcher).enqueue(new ApolloCall.Callback<AdminQuery.Data>() {
                @Override
                public void onResponse(@Nonnull Response<AdminQuery.Data> response) {
                    if (response.data().User() != null) {
                        List<DraftInfo> drafts = response.data().User().drafts().stream().map(d -> d.fragments().draftInfo()).collect(Collectors.toList());
                        EventBus.getDefault().postSticky(new DraftsEvent(drafts));
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });

    }

    public static void requestPrivatePublications(ResponseFetcher fetcher) {
        checkCredentials(() -> {
            AdminQuery query = AdminQuery.builder().id(USER_ID).build();

            apolloClient.query(query).responseFetcher(fetcher).enqueue(new ApolloCall.Callback<AdminQuery.Data>() {
                @Override
                public void onResponse(@Nonnull Response<AdminQuery.Data> response) {
                    if (response.data().User() != null) {
                        List<PublicationInfo> pubs = response.data().User().publications().stream().map(d -> d.fragments().publicationInfo()).collect(Collectors.toList());
                        EventBus.getDefault().postSticky(new PublicationsEvent(pubs));
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });
    }

    private static void checkCredentials(Runnable runnable) {
        if (tokenStorage.hasValidCredentials() && !USER_ID.isEmpty()) {
            runnable.run();
        } else {
            tokenStorage.getCredentials(new BaseCallback<Credentials, CredentialsManagerException>() {
                @Override
                public void onSuccess(Credentials payload) {
                    tokenStorage.saveCredentials(payload);
                    authorize(payload.getAccessToken(), runnable);
                }

                @Override
                public void onFailure(CredentialsManagerException error) {
                    EventBus.getDefault().post(error);
                }
            });
        }

    }

    public static void duplicateDraft(DraftInfo draft) {
        checkCredentials(() -> {
            AdminQuery query = AdminQuery.builder().id(USER_ID).build();
            CreateDraftMutation createDraft = CreateDraftMutation.builder()
                    .content(draft.content())
                    .ownerId(USER_ID)
                    .title(draft.title())
                    .type(DraftType.TUTORIAL)
                    .build();
            apolloClient.mutate(createDraft).refetchQueries(query).enqueue(new ApolloCall.Callback<CreateDraftMutation.Data>() {

                @Override
                public void onResponse(@Nonnull Response<CreateDraftMutation.Data> response) {
                    if (response.data().createDraft() != null) {
                        EventBus.getDefault().post(response.data().createDraft());
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });
    }


    public static void deletePublication(String id) {
        checkCredentials(() -> {
            AllPublicationsQuery query = AllPublicationsQuery.builder().build();
            DeletePublicationMutation delete = DeletePublicationMutation.builder().id(id).build();
            apolloClient.mutate(delete).refetchQueries(query).enqueue(new ApolloCall.Callback<DeletePublicationMutation.Data>() {

                @Override
                public void onResponse(@Nonnull Response<DeletePublicationMutation.Data> response) {
                    if (response.data().deletePublication() != null) {
                        EventBus.getDefault().post(response.data().deletePublication());
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });
    }

    public static void deleteDraft(String id) {
        checkCredentials(() -> {
            AllPublicDraftsQuery draftsQuery = AllPublicDraftsQuery.builder().build();
            AdminQuery adminQuery = AdminQuery.builder().id(USER_ID).build();
            DeleteDraftMutation delete = DeleteDraftMutation.builder().id(id).build();
            apolloClient.mutate(delete).refetchQueries(adminQuery, draftsQuery).enqueue(new ApolloCall.Callback<DeleteDraftMutation.Data>() {
                @Override
                public void onResponse(@Nonnull Response<DeleteDraftMutation.Data> response) {
                    if (response.data().deleteDraft() != null) {
                        EventBus.getDefault().post(response.data().deleteDraft());
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });
    }

    public static void unlikeDraft(String pubId, String receiverId) {

        checkCredentials(() -> {
            UnlikeDraftMutation like = UnlikeDraftMutation.builder()
                    .draftId(pubId)
                    .userId(USER_ID)
                    .build();

            CreateNotificationMutation create = CreateNotificationMutation.builder()
                    .message(pubId)
                    .receiverId(receiverId)
                    .senderId(USER_ID)
                    .type(NotificationType.UNLIKED_DRAFT)
                    .build();

            AllPublicationsQuery pubsQuery = AllPublicationsQuery.builder().build();
            AdminQuery adminQuery = AdminQuery.builder().id(USER_ID).build();

            apolloClient.mutate(like).enqueue(new ApolloCall.Callback<UnlikeDraftMutation.Data>() {
                @Override
                public void onResponse(@Nonnull Response<UnlikeDraftMutation.Data> res) {
                    if (res.data().removeFromUserOnLikedDraft() != null) {

                        apolloClient.mutate(create).refetchQueries(pubsQuery, adminQuery).enqueue(new ApolloCall.Callback<CreateNotificationMutation.Data>() {
                            @Override
                            public void onResponse(@Nonnull Response<CreateNotificationMutation.Data> response) {
                                if (response.data().createNotification() != null)
                                    EventBus.getDefault().post(res.data().removeFromUserOnLikedDraft());
                                else
                                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                            }

                            @Override
                            public void onFailure(@Nonnull ApolloException e) {
                                EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                            }
                        });

                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }

                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });

        });
    }


    public static void unlikePublication(String pubId, String receiverId) {

        checkCredentials(() -> {
            UnlikePublicationMutation like = UnlikePublicationMutation.builder()
                    .publicationId(pubId)
                    .userId(USER_ID)
                    .build();

            CreateNotificationMutation create = CreateNotificationMutation.builder()
                    .message(pubId)
                    .receiverId(receiverId)
                    .senderId(USER_ID)
                    .type(NotificationType.UNLIKED_PUBLICATION)
                    .build();

            AllPublicationsQuery pubsQuery = AllPublicationsQuery.builder().build();
            AdminQuery adminQuery = AdminQuery.builder().id(USER_ID).build();

            apolloClient.mutate(like).enqueue(new ApolloCall.Callback<UnlikePublicationMutation.Data>() {
                @Override
                public void onResponse(@Nonnull Response<UnlikePublicationMutation.Data> res) {
                    if (res.data().removeFromUserOnLikedPublication() != null) {

                        apolloClient.mutate(create).refetchQueries(pubsQuery, adminQuery).enqueue(new ApolloCall.Callback<CreateNotificationMutation.Data>() {
                            @Override
                            public void onResponse(@Nonnull Response<CreateNotificationMutation.Data> response) {
                                if (response.data().createNotification() != null)
                                    EventBus.getDefault().post(res.data().removeFromUserOnLikedPublication());
                                else
                                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                            }

                            @Override
                            public void onFailure(@Nonnull ApolloException e) {
                                EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                            }
                        });

                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }

                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });

        });
    }

    public static void likeDraft(String pubId, String receiverId) {

        checkCredentials(() -> {
            LikeDraftMutation like = LikeDraftMutation.builder()
                    .draftId(pubId)
                    .userId(USER_ID)
                    .build();

            CreateNotificationMutation create = CreateNotificationMutation.builder()
                    .message(pubId)
                    .receiverId(receiverId)
                    .senderId(USER_ID)
                    .type(NotificationType.LIKED_DRAFT)
                    .build();

            AllPublicationsQuery pubsQuery = AllPublicationsQuery.builder().build();
            AdminQuery adminQuery = AdminQuery.builder().id(USER_ID).build();

            apolloClient.mutate(like).enqueue(new ApolloCall.Callback<LikeDraftMutation.Data>() {
                @Override
                public void onResponse(@Nonnull Response<LikeDraftMutation.Data> res) {
                    if (res.data().addToUserOnLikedDraft() != null) {

                        apolloClient.mutate(create).refetchQueries(pubsQuery, adminQuery).enqueue(new ApolloCall.Callback<CreateNotificationMutation.Data>() {
                            @Override
                            public void onResponse(@Nonnull Response<CreateNotificationMutation.Data> response) {
                                if (response.data().createNotification() != null)
                                    EventBus.getDefault().post(res.data().addToUserOnLikedDraft());
                                else
                                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                            }

                            @Override
                            public void onFailure(@Nonnull ApolloException e) {
                                EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                            }
                        });

                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }

                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });

        });
    }

    public static void createNewPublication(Uri uri, DraftInfo draft) {
        new Thread(() -> {
            if (uri == null) {
                EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                return;
            }
            checkCredentials(() -> {
                try {
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(context.getContentResolver(), uri);
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream);
                    byte[] byteArray = stream.toByteArray();

                    OkHttpClient okHttpClient = new OkHttpClient();
                    RequestBody requestBody = new MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("data", "image.jpeg", RequestBody.create(MediaType.parse("image/jpeg"), byteArray))
                            .addFormDataPart("name", "image.jpeg")
                            .build();

                    Request request = new Request.Builder()
                            .url("https://api.graph.cool/file/v1/cjcnkimc02rhy0177ejct2ika")
                            .header("Accept", "application/json")
                            .header("Content-Type", "multipart/form-data")
                            .post(requestBody).build();
                    okhttp3.Response response = okHttpClient.newCall(request).execute();

                    if (!response.isSuccessful()) {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    } else {
                        String url = new JSONObject(response.body().string()).getString("url");

                        CreatePublicationMutation create = CreatePublicationMutation.builder()
                                .content(draft.content())
                                .image(url)
                                .ownerId(USER_ID)
                                .title(draft.title()).build();

                        AllPublicationsQuery draftsQuery = AllPublicationsQuery.builder().build();
                        AdminQuery adminQuery = AdminQuery.builder().id(USER_ID).build();
                        apolloClient.mutate(create).refetchQueries(draftsQuery, adminQuery).enqueue(new ApolloCall.Callback<CreatePublicationMutation.Data>() {
                            @Override
                            public void onResponse(@Nonnull Response<CreatePublicationMutation.Data> response) {
                                if (response.data().createPublication() != null) {
                                    EventBus.getDefault().post(response.data().createPublication());
                                } else {
                                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                                }
                            }

                            @Override
                            public void onFailure(@Nonnull ApolloException e) {
                                EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                            }
                        });
                    }

                } catch (IOException | JSONException e) {
                    e.printStackTrace();
                }

            });

        }).start();
    }


    public static void likePublication(String pubId, String receiverId) {

        checkCredentials(() -> {
            LikePublicationMutation like = LikePublicationMutation.builder()
                    .publicationId(pubId)
                    .userId(USER_ID)
                    .build();

            CreateNotificationMutation create = CreateNotificationMutation.builder()
                    .message(pubId)
                    .receiverId(receiverId)
                    .senderId(USER_ID)
                    .type(NotificationType.LIKED_PUBLICATION)
                    .build();

            AllPublicationsQuery pubsQuery = AllPublicationsQuery.builder().build();
            AdminQuery adminQuery = AdminQuery.builder().id(USER_ID).build();

            apolloClient.mutate(like).enqueue(new ApolloCall.Callback<LikePublicationMutation.Data>() {
                @Override
                public void onResponse(@Nonnull Response<LikePublicationMutation.Data> res) {
                    if (res.data().addToUserOnLikedPublication() != null) {

                        apolloClient.mutate(create).refetchQueries(pubsQuery, adminQuery).enqueue(new ApolloCall.Callback<CreateNotificationMutation.Data>() {
                            @Override
                            public void onResponse(@Nonnull Response<CreateNotificationMutation.Data> response) {
                                if (response.data().createNotification() != null)
                                    EventBus.getDefault().post(res.data().addToUserOnLikedPublication());
                                else
                                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                            }

                            @Override
                            public void onFailure(@Nonnull ApolloException e) {
                                EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                            }
                        });

                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }

                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });

        });
    }


    public static void updateDraft(UpdateDraftMutation update) {
        checkCredentials(() -> {
            AllPublicDraftsQuery draftsQuery = AllPublicDraftsQuery.builder().build();
            AdminQuery adminQuery = AdminQuery.builder().id(USER_ID).build();
            apolloClient.mutate(update).refetchQueries(adminQuery, draftsQuery).enqueue(new ApolloCall.Callback<UpdateDraftMutation.Data>() {
                @Override
                public void onResponse(@Nonnull Response<UpdateDraftMutation.Data> response) {
                    if (response.data().updateDraft() != null) {
                        EventBus.getDefault().post(response.data().updateDraft());
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });
    }

    public static void updateUser(String bio) {
        checkCredentials(() -> {
            AllPublicDraftsQuery draftsQuery = AllPublicDraftsQuery.builder().build();
            AllPublicationsQuery pubsQuery = AllPublicationsQuery.builder().build();
            AdminQuery adminQuery = AdminQuery.builder().id(USER_ID).build();
            UpdateUserMutation update = UpdateUserMutation.builder().bio(bio).id(USER_ID).build();
            apolloClient.mutate(update).refetchQueries(adminQuery, draftsQuery, pubsQuery).enqueue(new ApolloCall.Callback<UpdateUserMutation.Data>() {
                @Override
                public void onResponse(@Nonnull Response<UpdateUserMutation.Data> response) {
                    if (response.data().updateUser() != null) {
                        EventBus.getDefault().post(response.data().updateUser());
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });
    }


    public static void deleteNotification(String id) {
        checkCredentials(() -> {
            AdminQuery adminQuery = AdminQuery.builder().id(USER_ID).build();
            DeleteNotificationMutation delete = DeleteNotificationMutation.builder().id(id).build();
            apolloClient.mutate(delete).refetchQueries(adminQuery).enqueue(new ApolloCall.Callback<DeleteNotificationMutation.Data>() {
                @Override
                public void onResponse(@Nonnull Response<DeleteNotificationMutation.Data> response) {
                    if (response.data().deleteNotification() != null) {
                        EventBus.getDefault().post(response.data().deleteNotification());
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }

                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            });
        });
    }


    public static void requestAdmin(ResponseFetcher fetcher) {
        checkCredentials(() -> {
            AdminQuery adminQuery = AdminQuery.builder().id(USER_ID).build();
            apolloClient.query(adminQuery).responseFetcher(fetcher).enqueue(new ApolloCall.Callback<AdminQuery.Data>() {
                @Override
                public void onResponse(@Nonnull Response<AdminQuery.Data> response) {
                    if (response.data().User() != null) {
                        EventBus.getDefault().postSticky(response.data().User());
                    } else {
                        EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                    }
                }

                @Override
                public void onFailure(@Nonnull ApolloException e) {
                    EventBus.getDefault().post(new GlobalNetworkException(e.getMessage()));
                }
            });
        });
    }

    private static ApolloClient getApolloClient(String token) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request.Builder builder = original.newBuilder().method(original.method(), original.body());
                    builder.header("Authorization", "Bearer " + token);
                    return chain.proceed(builder.build());
                })
                .build();

        ApolloSqlHelper apolloSqlHelper = ApolloSqlHelper.create(context, "db_dgacademy");
        NormalizedCacheFactory sqlCache = new SqlNormalizedCacheFactory(apolloSqlHelper);

        CacheKeyResolver resolver = new CacheKeyResolver() {
            @Nonnull
            @Override
            public CacheKey fromFieldRecordSet(@Nonnull ResponseField field, @Nonnull Map<String, Object> recordSet) {
                return formatCacheKey((String) recordSet.get("id"));
            }

            @Nonnull
            @Override
            public CacheKey fromFieldArguments(@Nonnull ResponseField field, @Nonnull Operation.Variables variables) {
                return formatCacheKey((String) field.resolveArgument("id", variables));
            }

            private CacheKey formatCacheKey(String id) {
                if (id == null || id.isEmpty()) {
                    return CacheKey.NO_KEY;
                } else {
                    return CacheKey.from(id);
                }
            }
        };

        NormalizedCacheFactory memoryThenSqlCache = new LruNormalizedCacheFactory(
                EvictionPolicy.builder().maxSizeBytes(10 * 1024).build()
        ).chain(sqlCache);


        CustomTypeAdapter<Date> dateAdapterAdapter = new CustomTypeAdapter<Date>() {
            @Override
            public Date decode(String value) {
                try {
                    return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).parse(value);
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public String encode(Date value) {
                return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).format(value);
            }
        };


        return ApolloClient
                .builder()
                .defaultHttpCachePolicy(HttpCachePolicy.CACHE_FIRST)
                .serverUrl(GRAPHCOOL_URL)
                .addCustomTypeAdapter(CustomType.DATETIME, dateAdapterAdapter)
                .normalizedCache(memoryThenSqlCache, resolver)
                .okHttpClient(httpClient)
                .build();
    }


    private static void authorize(String token, Runnable runnable) {

        AuthenticateMutation authenticate = AuthenticateMutation.builder().token(token).build();
        apolloClient.mutate(authenticate).enqueue(new ApolloCall.Callback<AuthenticateMutation.Data>() {
            @Override
            public void onResponse(@Nonnull Response<AuthenticateMutation.Data> response) {
                if (response.data().authenticate() != null) {
                    apolloClient = getApolloClient(response.data().authenticate().token());
                    USER_ID = response.data().authenticate().id();
                    runnable.run();

                } else {
                    EventBus.getDefault().post(new GlobalNetworkException(NETWORK_ERROR));
                }
            }

            @Override
            public void onFailure(@Nonnull ApolloException e) {
                EventBus.getDefault().post(new GlobalNetworkException(e.getMessage()));
            }

        });
    }

}




