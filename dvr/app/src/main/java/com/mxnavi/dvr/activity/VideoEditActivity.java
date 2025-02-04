package com.mxnavi.dvr.activity;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.mxnavi.dvr.R;
import com.mxnavi.dvr.adapter.VideoAdapter;
import com.mxnavi.dvr.databinding.ActivityMediaEditBinding;
import com.mxnavi.dvr.utils.MediaSelector;
import com.mxnavi.dvr.utils.MediaDirUtil;
import com.mxnavi.dvr.utils.PhoneNumberManager;
import com.mxnavi.dvr.utils.TransportStatus;
import com.mxnavi.dvr.viewmodel.MediaViewModel;
import com.mxnavi.dvr.web.BaseApiResult;
import com.mxnavi.dvr.web.RecordBean;
import com.mxnavi.dvr.web.WebManager;
import com.storage.MediaBean;
import com.storage.util.Constant;
import com.storage.util.LocationRecorder;
import com.storage.util.NetworkUtil;
import com.storage.util.ToastUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.observers.DefaultObserver;
import io.reactivex.schedulers.Schedulers;

public class VideoEditActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "DVR-" + VideoEditActivity.class.getSimpleName();
    private long totalUploadSize = 0;
    private long uploadingSize = 0;
    private ActivityMediaEditBinding binding = null;
    private VideoAdapter adapter = null;
    private MediaViewModel viewModel = null;
    private MediaSelector selector = new MediaSelector();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        selector.setShow(true);
        adapter = new VideoAdapter(this, selector);

        binding = DataBindingUtil.setContentView(this, R.layout.activity_media_edit);
        binding.setSelectAll(false);
        binding.setEnable(true);
        binding.tvTitle.setText(getString(R.string.media_tab_title_video));
        binding.btnBack.setOnClickListener(this);
        binding.btnDelete.setOnClickListener(this);
        binding.btnUpload.setOnClickListener(this);
        binding.btnSelectAll.setOnClickListener(this);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);

        viewModel = new ViewModelProvider(this, new MediaViewModel.Factory(this)).get(MediaViewModel.class);
        viewModel.getQueriedVideos().observe(this, new Observer<List<MediaBean>>() {
            @Override
            public void onChanged(List<MediaBean> beans) {
                adapter.notifyDataSetChanged(beans);
                binding.pbLoading.setVisibility(View.GONE);
            }
        });

        viewModel.getVideoChange().observe(this, new Observer<Uri>() {
            @Override
            public void onChanged(Uri uri) {
                if (binding.getEnable()) {
                    viewModel.query(MediaBean.Type.VIDEO,
                            MediaDirUtil.getDir(VideoEditActivity.this, MediaDirUtil.Type.VIDEO),
                            Constant.OrderType.DESCENDING);
                }
            }
        });

        viewModel.getUploadBean().observe(this, new Observer<MediaBean>() {
            @Override
            public void onChanged(MediaBean bean) {
                Log.i(TAG, "upload: started bean = " + bean.getName());
                adapter.notifyUpload(bean, new TransportStatus(TransportStatus.STARTED, 0, false));
            }
        });

        viewModel.getUploadProgress().observe(this, new Observer<MediaViewModel.UploadProgress>() {
            @Override
            public void onChanged(MediaViewModel.UploadProgress uploadProgress) {
                Log.i(TAG, "upload: " + uploadProgress.toString());
                //adapter.notifyUpload(uploadProgress.bean, new TransportStatus(TransportStatus.PROGRESS, uploadProgress.progress, false));
            }
        });

        viewModel.getUploadResult().observe(this, new Observer<MediaViewModel.UploadResult>() {
            @Override
            public void onChanged(MediaViewModel.UploadResult uploadResult) {
                Log.i(TAG, "upload: " + uploadResult.toString());
                adapter.notifyUpload(uploadResult.bean, new TransportStatus(TransportStatus.COMPLETED, 100, uploadResult.isSuccess));
                uploadingSize += uploadResult.bean.getSize();

                if (uploadResult.isSuccess) {
                    List<LocationRecorder.LocationBean> locations = LocationRecorder.parseLocations(
                            LocationRecorder.getInstance().getDir() + File.separator + uploadResult.bean.getTitle() + ".json");

                    if (null != locations) {
                        List<RecordBean.Location> list = new ArrayList<>();

                        for (LocationRecorder.LocationBean l : locations) {
                            list.add(new RecordBean.Location(l.getTime(), l.getLatitude(), l.getLongitude()));
                        }

                        WebManager.getInstance().getWebService()
                                .uploadRecord(new RecordBean(
                                        uploadResult.bean.getTime(),
                                        uploadResult.bean.getUrl(),
                                        PhoneNumberManager.getInstance().getPhoneNumber(),
                                        list))
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(new DefaultObserver<BaseApiResult>() {
                                    @Override
                                    public void onNext(@NonNull BaseApiResult baseApiResult) {
                                        Log.i(TAG, baseApiResult.toString());
                                        if (baseApiResult.getOk() == 1) {
                                            ToastUtil.show(VideoEditActivity.this, R.string.upload_success);
                                        } else {
                                            ToastUtil.show(VideoEditActivity.this, R.string.upload_failed);
                                        }
                                    }

                                    @Override
                                    public void onError(@NonNull Throwable e) {
                                        Log.e(TAG, "onError: " + e.getMessage());
                                        ToastUtil.show(VideoEditActivity.this, R.string.upload_failed);
                                    }

                                    @Override
                                    public void onComplete() {
                                        Log.i(TAG, "onComplete");
                                    }
                                });
                    } else {
                        ToastUtil.show(VideoEditActivity.this, R.string.upload_failed);
                    }
                } else {
                    ToastUtil.show(VideoEditActivity.this, R.string.upload_failed);
                }

                if (uploadingSize >= totalUploadSize) {
                    selector.getBeans().clear();
                    adapter.notifyEnable(true);
                    binding.setSelectAll(false);
                    binding.setEnable(true);
                    viewModel.query(MediaBean.Type.VIDEO,
                            MediaDirUtil.getDir(VideoEditActivity.this, MediaDirUtil.Type.VIDEO),
                            Constant.OrderType.DESCENDING);
                }
            }
        });

        viewModel.getDeleteCount().observe(this, new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                Log.i(TAG, "delete: count = " + integer);
                selector.getBeans().clear();
                adapter.notifyEnable(true);
                binding.pbLoading.setVisibility(View.GONE);
                binding.setSelectAll(false);
                binding.setEnable(true);
                ToastUtil.show(VideoEditActivity.this, R.string.delete_success);
                viewModel.query(MediaBean.Type.VIDEO,
                        MediaDirUtil.getDir(VideoEditActivity.this, MediaDirUtil.Type.VIDEO),
                        Constant.OrderType.DESCENDING);
            }
        });

        viewModel.query(MediaBean.Type.VIDEO,
                MediaDirUtil.getDir(VideoEditActivity.this, MediaDirUtil.Type.VIDEO),
                Constant.OrderType.DESCENDING);
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy");
        viewModel.clearUpload();
        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        if (binding.btnBack.equals(v)){
            finish();
        } else if (binding.btnSelectAll.equals(v)) {
            binding.setSelectAll(!binding.getSelectAll());
            adapter.notifySelectAll(binding.getSelectAll());
        } else if (binding.btnUpload.equals(v)) {
            if (!NetworkUtil.isNetworkAvailable(this)) {
                ToastUtil.show(this, R.string.network_error);
                return;
            }

            if (null == selector.getBeans() || selector.getBeans().isEmpty()) {
                return;
            }

            binding.setEnable(false);
            adapter.notifyEnable(false);
            uploadingSize = 0;
            totalUploadSize = 0;

            for (MediaBean bean : selector.getBeans()) {
                totalUploadSize += bean.getSize();
            }

            viewModel.upload(selector.getBeans());
        } else if (binding.btnDelete.equals(v)) {
            List<MediaBean> beans = new ArrayList<>(selector.getBeans());

            if (beans.isEmpty()) {
                return;
            }

            binding.setEnable(false);
            binding.pbLoading.setVisibility(View.VISIBLE);
            adapter.notifyEnable(false);
            viewModel.delete(beans);
        }
    }
}