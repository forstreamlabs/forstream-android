package io.forstream.view.main.channels;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.login.LoginManager;
import com.facebook.login.LoginResult;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import io.forstream.R;
import io.forstream.api.model.Channel;
import io.forstream.api.model.ChannelTarget;
import io.forstream.common.BaseFragment;
import io.forstream.common.livedata.list.ListUpdateType;
import io.forstream.util.AlertUtils;
import io.forstream.util.UIUtils;
import io.forstream.util.component.SpaceItemDecoration;
import io.forstream.view.main.channels.facebook.ChannelTargetDialogFragment;

public class ChannelsFragment extends BaseFragment implements SwipeRefreshLayout.OnRefreshListener, ChannelsAdapter.Listener, ChannelTargetDialogFragment.Listener {

  private static final int YOUTUBE_CHANNEL_SIGN_IN_REQUEST_CODE = 1;

  private static final String YOUTUBE_SCOPE_MANAGE = "https://www.googleapis.com/auth/youtube";

  private static final String FACEBOOK_SCOPE_PUBLISH_VIDEO = "publish_video";
  private static final String FACEBOOK_SCOPE_PAGES_SHOW_LIST = "pages_show_list";
  private static final String FACEBOOK_SCOPE_PAGES_READ_ENGAGEMENT = "pages_read_engagement";
  private static final String FACEBOOK_SCOPE_PAGES_MANAGE_POST = "pages_manage_posts";

  @BindView(R.id.swipe_refresh_layout) SwipeRefreshLayout swipeRefreshLayout;
  @BindView(R.id.channels_view) RecyclerView channelsView;

  @Inject Context context;
  @Inject ChannelsViewModel channelsViewModel;
  @Inject ChannelsAdapter channelsAdapter;

  private GoogleSignInClient youtubeChannelSignInClient;
  private CallbackManager callbackManager = CallbackManager.Factory.create();
  private ChannelTargetDialogFragment channelTargetDialogFragment;
  private Channel selectedChannel;
  private String selectedAccessToken;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.fragment_channels, container, false);
    ButterKnife.bind(this, view);

    UIUtils.defaultSwipeRefreshLayout(swipeRefreshLayout, this);
    setupObservers();
    setupViews();
    setupYouTubeChannelSignIn();
    setupContent();
    return view;
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    callbackManager.onActivityResult(requestCode, resultCode, data);
    super.onActivityResult(requestCode, resultCode, data);
    switch (requestCode) {
      case YOUTUBE_CHANNEL_SIGN_IN_REQUEST_CODE:
        Task<GoogleSignInAccount> youtubeChannelSignInTask = GoogleSignIn.getSignedInAccountFromIntent(data);
        handleYouTubeChannelSignInResult(youtubeChannelSignInTask);
        break;
    }
  }

  @Override
  public void onRefresh() {
    setupContent();
  }

  @Override
  public void onChannelClick(Channel channel) {
    selectedChannel = channel;
    switch (channel.getIdentifier()) {
      case YOUTUBE:
        youtubeChannelSignInClient.revokeAccess();
        Intent signInIntent = youtubeChannelSignInClient.getSignInIntent();
        startActivityForResult(signInIntent, YOUTUBE_CHANNEL_SIGN_IN_REQUEST_CODE);
        break;
      case FACEBOOK:
        List<String> scopes = new ArrayList<>();
        scopes.add(FACEBOOK_SCOPE_PUBLISH_VIDEO);
        scopes.add(FACEBOOK_SCOPE_PAGES_SHOW_LIST);
        scopes.add(FACEBOOK_SCOPE_PAGES_READ_ENGAGEMENT);
        scopes.add(FACEBOOK_SCOPE_PAGES_MANAGE_POST);
        LoginManager loginManager = LoginManager.getInstance();
        loginManager.registerCallback(callbackManager, new ConnectFacebookCallback());
        loginManager.logIn(this, scopes);
        break;
    }
  }

  @Override
  public void onConnectButtonClick(ChannelTarget channelTarget) {
    channelsViewModel.connectFacebookChannel(selectedAccessToken, channelTarget.getId());
  }

  private void setupObservers() {
    channelsViewModel.getViewItems().observe(getViewLifecycleOwner(), channelsHolder -> {
      if (!ListUpdateType.NONE.equals(channelsHolder.getUpdateType())) {
        swipeRefreshLayout.setRefreshing(false);
        channelsAdapter.setViewItems(channelsHolder.getItems());
        channelsHolder.applyChanges(channelsAdapter);
      }
    });
    channelsViewModel.getFacebookTargets().observe(getViewLifecycleOwner(), channelTargets -> {
      if (channelTargets.size() == 1) {
        channelsViewModel.connectFacebookChannel(selectedAccessToken, channelTargets.get(0).getId());
      } else {
        channelTargetDialogFragment = new ChannelTargetDialogFragment(context);
        channelTargetDialogFragment.setListener(this);
        channelTargetDialogFragment.setChannel(selectedChannel);
        channelTargetDialogFragment.setChannelTargets(channelTargets);
        channelTargetDialogFragment.show(getActivity().getSupportFragmentManager(), ChannelsFragment.class.getSimpleName());
      }
    });
    channelsViewModel.getChannelConnected().observe(getViewLifecycleOwner(), connectedChannel -> {
      if (channelTargetDialogFragment != null) {
        channelTargetDialogFragment.dismiss();
      }
    });
    channelsViewModel.getError().observe(getViewLifecycleOwner(), throwable -> {
      swipeRefreshLayout.setRefreshing(false);
      AlertUtils.alert(context, throwable);
    });
  }

  private void setupViews() {
    channelsAdapter.setListener(this);

    LinearLayoutManager layoutManager = new LinearLayoutManager(context);
    SpaceItemDecoration itemDecoration = new SpaceItemDecoration(context.getResources().getDimensionPixelSize(R.dimen.margin_md), layoutManager.getOrientation());
    channelsView.setLayoutManager(layoutManager);
    channelsView.addItemDecoration(itemDecoration);
    channelsView.setAdapter(channelsAdapter);
    RecyclerView.ItemAnimator itemAnimator = channelsView.getItemAnimator();
    if (itemAnimator instanceof SimpleItemAnimator) {
      ((SimpleItemAnimator) itemAnimator).setSupportsChangeAnimations(false);
    }
  }

  private void setupYouTubeChannelSignIn() {
    String googleAuthClientId = getString(R.string.default_web_client_id);
    GoogleSignInOptions signInOptions = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
      .requestEmail()
      .requestIdToken(googleAuthClientId)
      .requestServerAuthCode(googleAuthClientId)
      .requestScopes(new Scope(YOUTUBE_SCOPE_MANAGE))
      .build();
    youtubeChannelSignInClient = GoogleSignIn.getClient(context, signInOptions);
  }

  private void setupContent() {
    channelsViewModel.loadViewItems();
  }

  private void handleYouTubeChannelSignInResult(Task<GoogleSignInAccount> completedTask) {
    try {
      GoogleSignInAccount googleSignInAccount = completedTask.getResult(ApiException.class);
      channelsViewModel.connectYouTubeChannel(googleSignInAccount.getServerAuthCode());
    } catch (ApiException e) {
      e.printStackTrace();
    }
  }

  private class ConnectFacebookCallback implements FacebookCallback<LoginResult> {
    @Override
    public void onSuccess(LoginResult loginResult) {
      selectedAccessToken = loginResult.getAccessToken().getToken();
      channelsViewModel.listFacebookTargets(selectedAccessToken);
    }

    @Override
    public void onCancel() {

    }

    @Override
    public void onError(FacebookException error) {
      AlertUtils.alert(context, error);
    }
  }
}
