package com.dg.dgacademy.activities;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.widget.TextView;

import com.dg.dgacademy.DgApplication;
import com.dg.dgacademy.R;
import com.dg.dgacademy.model.Draft;
import com.dg.dgacademy.model.DraftsEvent;
import com.dg.dgacademy.model.Publication;
import com.dg.dgacademy.model.PublicationsEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;


public class DashboardActivity extends AppCompatActivity {

    @BindView(R.id.total_likes) TextView totalLikes;
    @BindView(R.id.total_your_likes) TextView totalYourLikes;
    @BindView(R.id.total_publications) TextView totalPublications;
    @BindView(R.id.total_drafts) TextView totalDrafts;

    @BindView(R.id.toolbar) Toolbar toolbar;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        ButterKnife.bind(this);
        DgApplication.requestPublicDrafts();
        DgApplication.requestPublicPublications();

        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);

    }

    @OnClick(R.id.toolbar_menu)
    public void onClickToolbarMenu() {
        startActivity(new Intent(this, MenuActivity.class));
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    public void onDraftsRequest(DraftsEvent event) {
        totalDrafts.setText(String.valueOf(event.drafts.size()));
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    public void onPublicationsRequest(PublicationsEvent event) {
        totalPublications.setText(String.valueOf(event.publications.size()));
    }
}
