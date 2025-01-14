/*
 * Copyright 2024 Andrey Novikov
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package mobi.maptrek.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.oscim.android.canvas.AndroidBitmap;
import org.oscim.backend.canvas.Bitmap;
import org.oscim.backend.canvas.Color;
import org.oscim.core.GeoPoint;
import org.oscim.core.MapPosition;

import java.util.ArrayList;
import java.util.Stack;

import mobi.maptrek.MapHolder;
import mobi.maptrek.MapTrek;
import mobi.maptrek.R;
import mobi.maptrek.data.Route;
import mobi.maptrek.databinding.FragmentRulerBinding;
import mobi.maptrek.layers.RouteLayer;
import mobi.maptrek.layers.marker.ItemizedLayer;
import mobi.maptrek.layers.marker.MarkerItem;
import mobi.maptrek.layers.marker.MarkerSymbol;
import mobi.maptrek.util.MarkerFactory;
import mobi.maptrek.util.StringFormatter;

public class Ruler extends Fragment implements ItemizedLayer.OnItemGestureListener<MarkerItem> {
    private MapHolder mMapHolder;

    private MapPosition mMapPosition;
    private RouteLayer mRouteLayer;
    private ItemizedLayer<MarkerItem> mPointLayer;
    private RulerViewModel viewModel;
    private FragmentRulerBinding viewBinding;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMapPosition = new MapPosition();
        viewModel = new ViewModelProvider(this).get(RulerViewModel.class);
    }

    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        viewBinding = FragmentRulerBinding.inflate(inflater, container, false);
        return viewBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewBinding.addButton.setOnClickListener(v -> {
            if (mMapHolder.getMap().getMapPosition(mMapPosition)) {
                GeoPoint point = mMapPosition.getGeoPoint();
                viewModel.route.addInstruction(point);
                MarkerItem marker = new MarkerItem(point, null, null, point);
                mPointLayer.addItem(marker);
                viewModel.pointHistory.push(point);
                mMapHolder.getMap().updateMap(true);
                updateTrackMeasurements();
            }
        });

        viewBinding.insertButton.setOnClickListener(v -> {
            if (mMapHolder.getMap().getMapPosition(mMapPosition)) {
                GeoPoint point = mMapPosition.getGeoPoint();
                viewModel.route.insertInstruction(point);
                MarkerItem marker = new MarkerItem(point, null, null, point);
                mPointLayer.addItem(marker);
                viewModel.pointHistory.push(point);
                mMapHolder.getMap().updateMap(true);
                updateTrackMeasurements();
            }
        });

        viewBinding.removeButton.setOnClickListener(v -> {
            if (viewModel.route.length() > 0) {
                mMapHolder.getMap().getMapPosition(mMapPosition);
                Route.Instruction instruction = viewModel.route.getNearestInstruction(mMapPosition.getGeoPoint());
                viewModel.route.removeInstruction(instruction);
                MarkerItem marker = mPointLayer.getByUid(instruction);
                mPointLayer.removeItem(marker);
                viewModel.pointHistory.remove(instruction);
                mMapHolder.getMap().updateMap(true);
                mMapPosition = new MapPosition();
                updateTrackMeasurements();
            }
        });

        viewBinding.undoButton.setOnClickListener(v -> {
            if (!viewModel.pointHistory.isEmpty()) {
                GeoPoint point = viewModel.pointHistory.pop();
                Route.Instruction instruction = viewModel.route.getNearestInstruction(point);
                if (instruction == null) {
                    viewModel.pointHistory.clear();
                    return;
                }
                viewModel.route.removeInstruction(instruction);
                MarkerItem marker = mPointLayer.getByUid(instruction);
                mPointLayer.removeItem(marker);
                mMapHolder.getMap().updateMap(true);
                mMapPosition = new MapPosition();
                updateTrackMeasurements();
            }
        });
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        try {
            mMapHolder = (MapHolder) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context + " must implement MapHolder");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mRouteLayer = new RouteLayer(mMapHolder.getMap(), Color.RED, 5, viewModel.route);
        mMapHolder.getMap().layers().add(mRouteLayer);
        Bitmap bitmap = new AndroidBitmap(MarkerFactory.getMarkerSymbol(requireContext(), R.drawable.dot_black, Color.RED));
        MarkerSymbol symbol = new MarkerSymbol(bitmap, MarkerItem.HotspotPlace.CENTER);
        ArrayList<MarkerItem> items = new ArrayList<>(viewModel.route.length());
        for (GeoPoint point : viewModel.route.getCoordinates()) {
            items.add(new MarkerItem(point, null, null, point));
        }
        mPointLayer = new ItemizedLayer<>(mMapHolder.getMap(), items, symbol, MapTrek.density, this);
        mMapHolder.getMap().layers().add(mPointLayer);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateTrackMeasurements();
        mMapHolder.setObjectInteractionEnabled(false);
    }

    @Override
    public void onPause() {
        super.onPause();
        mMapHolder.setObjectInteractionEnabled(true);
    }

    @Override
    public void onStop() {
        super.onStop();
        mMapHolder.getMap().layers().remove(mRouteLayer);
        mRouteLayer.onDetach();
        mRouteLayer = null;
        mMapHolder.getMap().layers().remove(mPointLayer);
        mPointLayer.onDetach();
        mPointLayer = null;
        mMapHolder.getMap().updateMap(true);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mMapHolder = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mMapPosition = null;
    }

    private void updateTrackMeasurements() {
        int length = Math.max(viewModel.route.length() - 1, 0);
        viewBinding.distance.setText(StringFormatter.distanceHP(viewModel.route.distance));
        viewBinding.size.setText(getResources().getQuantityString(R.plurals.numberOfSegments, length, length));
    }

    @Override
    public boolean onItemSingleTapUp(int index, MarkerItem item) {
        return false;
    }

    @Override
    public boolean onItemLongPress(int index, MarkerItem item) {
        return false;
    }

    public static class RulerViewModel extends ViewModel {
        private final Route route = new Route();
        private final Stack<GeoPoint> pointHistory = new Stack<>();
    }
}
