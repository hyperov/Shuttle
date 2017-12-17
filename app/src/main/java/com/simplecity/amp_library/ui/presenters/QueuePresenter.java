package com.simplecity.amp_library.ui.presenters;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.annotation.NonNull;
import android.support.v7.widget.PopupMenu;
import android.view.MenuItem;
import android.view.View;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.bumptech.glide.RequestManager;
import com.cantrowitz.rxbroadcast.RxBroadcast;
import com.simplecity.amp_library.ShuttleApplication;
import com.simplecity.amp_library.model.Playlist;
import com.simplecity.amp_library.model.Song;
import com.simplecity.amp_library.playback.MusicService;
import com.simplecity.amp_library.ui.modelviews.SongView;
import com.simplecity.amp_library.ui.views.QueueView;
import com.simplecity.amp_library.utils.ContextualToolbarHelper;
import com.simplecity.amp_library.utils.MenuUtils;
import com.simplecity.amp_library.utils.MusicUtils;
import com.simplecity.amp_library.utils.PlaylistUtils;
import com.simplecity.amp_library.utils.ShuttleUtils;
import com.simplecityapps.recycler_adapter.model.ViewModel;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.BackpressureStrategy;
import io.reactivex.android.schedulers.AndroidSchedulers;

public class QueuePresenter extends Presenter<QueueView> {

    private static final String TAG = "QueuePresenter";

    private RequestManager requestManager;

    private ContextualToolbarHelper<Song> contextualToolbarHelper;

    public QueuePresenter(RequestManager requestManager, ContextualToolbarHelper<Song> contextualToolbarHelper) {
        this.requestManager = requestManager;
        this.contextualToolbarHelper = contextualToolbarHelper;
    }

    private List<ViewModel> data = new ArrayList<>();

    @Override
    public void bindView(@NonNull QueueView view) {
        super.bindView(view);

        IntentFilter filter = new IntentFilter();
        filter.addAction(MusicService.InternalIntents.META_CHANGED);
        filter.addAction(MusicService.InternalIntents.REPEAT_CHANGED);
        filter.addAction(MusicService.InternalIntents.SHUFFLE_CHANGED);
        filter.addAction(MusicService.InternalIntents.QUEUE_CHANGED);
        filter.addAction(MusicService.InternalIntents.SERVICE_CONNECTED);

        addDisposable(RxBroadcast.fromBroadcast(ShuttleApplication.getInstance(), filter)
                .startWith(new Intent(MusicService.InternalIntents.QUEUE_CHANGED))
                .toFlowable(BackpressureStrategy.LATEST)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(intent -> {
                    final String action = intent.getAction();

                    QueueView queueView = getView();
                    if (queueView == null) {
                        return;
                    }

                    if (action != null) {
                        switch (action) {
                            case MusicService.InternalIntents.META_CHANGED:
                                queueView.updateQueuePosition(MusicUtils.getQueuePosition(), false);
                                break;
                            case MusicService.InternalIntents.QUEUE_CHANGED:
                                if (!intent.getBooleanExtra(MusicService.FROM_USER, false)) {
                                    loadData();
                                }
                                break;
                            case MusicService.InternalIntents.SERVICE_CONNECTED:
                            case MusicService.InternalIntents.REPEAT_CHANGED:
                            case MusicService.InternalIntents.SHUFFLE_CHANGED:
                                loadData();
                                break;
                        }
                    }
                }));
    }

    public void saveQueue(Context context) {
        PlaylistUtils.createPlaylistDialog(context, MusicUtils.getQueue());
    }

    public void saveQueue(Context context, MenuItem item) {
        Playlist playlist = (Playlist) item.getIntent().getSerializableExtra(PlaylistUtils.ARG_PLAYLIST);
        PlaylistUtils.addToPlaylist(context, playlist, MusicUtils.getQueue());
    }

    public void clearQueue() {
        MusicUtils.clearQueue();
    }

    public void removeFromQueue(int position, Song song) {
        QueueView queueView = getView();
        if (queueView != null) {
            queueView.removeFromQueue(position);
        }
        MusicUtils.removeFromQueue(position);
    }

    private void playNext(int position) {
        int newPosition = MusicUtils.getQueuePosition() + 1;

        QueueView queueView = getView();
        if (queueView != null) {
            queueView.moveQueueItem(position, newPosition);
        }

        MusicUtils.moveQueueItem(position, newPosition);
    }

    private void loadData() {
        QueueView queueView = getView();
        data = Stream.of(MusicUtils.getQueue())
                .map(song -> {
                    SongView songView = new SongView(song, requestManager) {
                        @Override
                        public boolean equals(Object o) {
                            // It's possible to have multiple SongViews with the same (duplicate) songs in the queue.
                            // When that occurs, there's not currently a way to tell the two SongViews apart - which
                            // can result in an adapter inconsistency. This fix just ensures no two SongViews in the queue
                            // are considered to be the same. We lose some RV optimisations here, but at least we don't crash.
                            return false;
                        }
                    };
                    songView.setClickListener(clickListener);
                    songView.showAlbumArt(true);
                    songView.setEditable(true);

                    return songView;
                })
                .collect(Collectors.toList());
        if (queueView != null) {
            queueView.loadData(data, MusicUtils.getQueuePosition());
        }
    }

    private SongView.ClickListener clickListener = new SongView.ClickListener() {
        @Override
        public void onSongClick(int position, SongView songView) {
            if (!contextualToolbarHelper.handleClick(position, songView, songView.song)) {
                MusicUtils.setQueuePosition(position);
                QueueView queueView = getView();
                if (queueView != null) {
                    queueView.updateQueuePosition(position, true);
                }
            }
        }

        @Override
        public boolean onSongLongClick(int position, SongView songView) {
            return contextualToolbarHelper.handleLongClick(position, songView, songView.song);
        }

        @Override
        public void onSongOverflowClick(int position, View v, Song song) {
            PopupMenu menu = new PopupMenu(v.getContext(), v);
            MenuUtils.setupSongMenu(menu, true);
            menu.setOnMenuItemClickListener(MenuUtils.getSongMenuClickListener(v.getContext(), song,
                    taggerDialog -> {
                        QueueView queueView = getView();
                        if (queueView != null) {
                            if (!ShuttleUtils.isUpgraded()) {
                                queueView.showUpgradeDialog();
                            } else {
                                queueView.showTaggerDialog(taggerDialog);
                            }
                        }
                    },
                    () -> removeFromQueue(position, song),
                    () -> playNext(position)));
            menu.show();
        }

        @Override
        public void onStartDrag(SongView.ViewHolder holder) {
            QueueView queueView = getView();
            if (queueView != null) {
                queueView.startDrag(holder);
            }
        }
    };
}