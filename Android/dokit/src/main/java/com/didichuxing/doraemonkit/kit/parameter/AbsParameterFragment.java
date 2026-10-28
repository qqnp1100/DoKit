package com.didichuxing.doraemonkit.kit.parameter;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.View;

import com.didichuxing.doraemonkit.R;
import com.didichuxing.doraemonkit.kit.core.BaseFragment;
import com.didichuxing.doraemonkit.kit.performance.PerformanceDokitViewManager;
import com.didichuxing.doraemonkit.kit.performance.PerformanceFragmentCloseListener;
import com.didichuxing.doraemonkit.kit.core.SettingItem;
import com.didichuxing.doraemonkit.kit.core.SettingItemAdapter;
import com.didichuxing.doraemonkit.widget.titlebar.HomeTitleBar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


public abstract class AbsParameterFragment extends BaseFragment implements PerformanceFragmentCloseListener {


    private SettingItemAdapter mSettingItemAdapter;
    private RecyclerView mSettingList;


    @Override
    protected int onRequestLayout() {
        return R.layout.dk_fragment_parameter;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initView();
    }

    @StringRes
    protected abstract int getTitle();


    protected abstract int getPerformanceType();

    protected abstract Collection<SettingItem> getSettingItems(List<SettingItem> list);

    protected abstract SettingItemAdapter.OnSettingItemSwitchListener getItemSwitchListener();

    protected abstract SettingItemAdapter.OnSettingItemClickListener getItemClickListener();

    protected void openChartPage(@StringRes int title, int type) {

        PerformanceDokitViewManager.open(type, getString(title), this);
    }


    protected void closeChartPage() {
        PerformanceDokitViewManager.close(getPerformanceType(), getString(getTitle()));
        //RealTimeChartDokitView.closeChartPage();
    }

    private void initView() {
        HomeTitleBar titleBar = findViewById(R.id.title_bar);
        titleBar.setTitle(getTitle());
        titleBar.setListener(new HomeTitleBar.OnTitleBarClickListener() {
            @Override
            public void onRightClick() {
                getActivity().finish();
            }
        });

        mSettingList = findViewById(R.id.setting_list);
        mSettingList.setLayoutManager(new LinearLayoutManager(getContext()));
        mSettingItemAdapter = new SettingItemAdapter(getContext());
        mSettingItemAdapter.append(getSettingItems(new ArrayList<SettingItem>()));

        // Performance logs and network configuration use app-private storage, which
        // needs no storage permission, including on devices with scoped storage.
        mSettingItemAdapter.setOnSettingItemSwitchListener(new SettingItemAdapter.OnSettingItemSwitchListener() {
            @Override
            public void onSettingItemSwitch(View view, SettingItem data, boolean on) {
                SettingItemAdapter.OnSettingItemSwitchListener itemSwitchListener = getItemSwitchListener();
                if (itemSwitchListener != null) {
                    itemSwitchListener.onSettingItemSwitch(view, data, on);
                }
            }
        });
        mSettingItemAdapter.setOnSettingItemClickListener(new SettingItemAdapter.OnSettingItemClickListener() {
            @Override
            public void onSettingItemClick(View view, SettingItem data) {
                SettingItemAdapter.OnSettingItemClickListener itemClickListener = getItemClickListener();
                if (itemClickListener != null) {
                    itemClickListener.onSettingItemClick(view, data);
                }

            }
        });
        mSettingList.setAdapter(mSettingItemAdapter);
    }

    @Override
    public void onClose(int performanceType) {
        if (performanceType != getPerformanceType()) {
            return;
        }
        if (mSettingList == null || mSettingList.isComputingLayout()) {
            return;
        }
        if (mSettingItemAdapter == null) {
            return;
        }
        if (!mSettingItemAdapter.getData().get(0).isChecked) {
            return;
        }
        mSettingItemAdapter.getData().get(0).isChecked = false;
        mSettingItemAdapter.notifyItemChanged(0);
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mSettingItemAdapter = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        //移除监听
        PerformanceDokitViewManager.onPerformanceSettingFragmentDestroy(this);
    }
}
