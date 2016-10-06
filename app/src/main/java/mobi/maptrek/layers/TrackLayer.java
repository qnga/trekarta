package mobi.maptrek.layers;

import org.oscim.backend.canvas.Paint.Cap;
import org.oscim.core.MapPosition;
import org.oscim.core.MercatorProjection;
import org.oscim.core.Tile;
import org.oscim.layers.Layer;
import org.oscim.map.Map;
import org.oscim.renderer.BucketRenderer;
import org.oscim.renderer.GLViewport;
import org.oscim.renderer.bucket.LineBucket;
import org.oscim.renderer.bucket.RenderBuckets;
import org.oscim.theme.styles.LineStyle;
import org.oscim.utils.FastMath;
import org.oscim.utils.async.SimpleWorker;
import org.oscim.utils.geom.LineClipper;

import mobi.maptrek.data.Track;

/**
 * This class draws a path line in given color.
 */
public class TrackLayer extends Layer {

    /**
     * Stores points, converted to the map projection.
     */
    final Track mTrack;
    private boolean mUpdatePoints;

    /**
     * Line style
     */
    LineStyle mLineStyle;

    private final Worker mWorker;

    public TrackLayer(Map map, Track track) {
        super(map);
        mWorker = new Worker(map);
        mLineStyle = new LineStyle(track.style.color, track.style.width, Cap.BUTT);
        mRenderer = new RenderPath();
        mTrack = track;
        updatePoints();
    }

    void updatePoints() {
        mWorker.submit(10);
        mUpdatePoints = true;
    }

    public Track getTrack() {
        return mTrack;
    }

    public void setColor(int color) {
        mLineStyle = new LineStyle(color, mLineStyle.width, mLineStyle.cap);
        mWorker.submit(10);
    }

    /***
     * everything below runs on GL- and Worker-Thread
     ***/
    private final class RenderPath extends BucketRenderer {

        RenderPath() {

            buckets.addLineBucket(0, mLineStyle);
        }

        private int mCurX = -1;
        private int mCurY = -1;
        private int mCurZ = -1;

        @Override
        public synchronized void update(GLViewport v) {
            int tz = 1 << v.pos.zoomLevel;
            int tx = (int) (v.pos.x * tz);
            int ty = (int) (v.pos.y * tz);

            // update layers when map moved by at least one tile
            if ((tx != mCurX || ty != mCurY || tz != mCurZ)) {
                mWorker.submit(100);
                mCurX = tx;
                mCurY = ty;
                mCurZ = tz;
            }

            Task t = mWorker.poll();
            if (t == null)
                return;

            // keep position to render relative to current state
            mMapPosition.copy(t.pos);

            // compile new layers
            buckets.set(t.bucket.get());
            compile();
        }
    }

    final static class Task {
        RenderBuckets bucket = new RenderBuckets();
        MapPosition pos = new MapPosition();
    }

    private final class Worker extends SimpleWorker<Task> {
        private static final int GROW_INDICES = 32;
        // limit coords
        private final int max = 2048;

        Worker(Map map) {
            super(map, 0, new Task(), new Task());
            mClipper = new LineClipper(-max, -max, max, max);
            mPPoints = new float[0];
        }

        private static final int MIN_DIST = 3;

        // pre-projected points
        private double[] mPreprojected = new double[2];

        // projected points
        private float[] mPPoints;
        private final LineClipper mClipper;
        private int mNumPoints;

        // tear index
        private int[] index = new int[1];

        @Override
        public boolean doWork(Task task) {

            int size = mNumPoints;

            if (mUpdatePoints) {
                synchronized (mTrack) {
                    mUpdatePoints = false;
                    int indexPos = 0;
                    index[indexPos] = -1;
                    mNumPoints = size = mTrack.points.size();

                    double[] points = mPreprojected;

                    if (size * 2 >= points.length) {
                        points = mPreprojected = new double[size * 2];
                        mPPoints = new float[size * 2];
                    }

                    for (int i = 0; i < size; i++) {
                        Track.TrackPoint point = mTrack.points.get(i);
                        MercatorProjection.project(point, points, i);
                        if (!point.continuous && i > 0) {
                            if (indexPos + 1 >= index.length)
                                ensureIndexSize(indexPos + 1, true);
                            index[indexPos] = i;
                            indexPos++;
                            if (index.length > indexPos + 1)
                                index[indexPos] = -1;
                        }

                    }
                }
            }

            if (size == 0) {
                if (task.bucket.get() != null) {
                    task.bucket.clear();
                    mMap.render();
                }
                return true;
            }

            RenderBuckets layers = task.bucket;

            LineBucket ll = layers.getLineBucket(0);
            ll.line = mLineStyle;
            ll.scale = ll.line.width;

            mMap.getMapPosition(task.pos);

            int zoomlevel = task.pos.zoomLevel;
            task.pos.scale = 1 << zoomlevel;

            double mx = task.pos.x;
            double my = task.pos.y;
            double scale = Tile.SIZE * task.pos.scale;

            // flip around dateline
            int flip = 0;
            int maxx = Tile.SIZE << (zoomlevel - 1);

            int x = (int) ((mPreprojected[0] - mx) * scale);
            int y = (int) ((mPreprojected[1] - my) * scale);

            if (x > maxx) {
                x -= (maxx * 2);
                flip = -1;
            } else if (x < -maxx) {
                x += (maxx * 2);
                flip = 1;
            }

            mClipper.clipStart(x, y);

            float[] projected = mPPoints;
            int i = addPoint(projected, 0, x, y);

            float prevX = x;
            float prevY = y;

            float[] segment = null;

            int indexPos = 0;

            for (int j = 2; j < size * 2; j += 2) {
                //noinspection PointlessArithmeticExpression
                x = (int) ((mPreprojected[j + 0] - mx) * scale);
                y = (int) ((mPreprojected[j + 1] - my) * scale);

                if (index[indexPos] == (j >> 1)) {
                    if (i > 2)
                        ll.addLine(projected, i, false);

                    mClipper.clipStart(x, y);
                    i = addPoint(projected, 0, x, y);
                    indexPos++;
                    continue;
                }

                int flipDirection = 0;
                if (x > maxx) {
                    x -= maxx * 2;
                    flipDirection = -1;
                } else if (x < -maxx) {
                    x += maxx * 2;
                    flipDirection = 1;
                }

                if (flip != flipDirection) {
                    flip = flipDirection;
                    if (i > 2)
                        ll.addLine(projected, i, false);

                    mClipper.clipStart(x, y);
                    i = addPoint(projected, 0, x, y);
                    continue;
                }

                int clip = mClipper.clipNext(x, y);
                if (clip < 1) {
                    if (i > 2)
                        ll.addLine(projected, i, false);

                    if (clip < 0) {
                        /* add line segment */
                        segment = mClipper.getLine(segment, 0);
                        ll.addLine(segment, 4, false);
                        prevX = mClipper.outX2;
                        prevY = mClipper.outY2;
                    }
                    i = 0;
                    continue;
                }

                float dx = x - prevX;
                float dy = y - prevY;
                if ((i == 0) || FastMath.absMaxCmp(dx, dy, MIN_DIST)) {
                    projected[i++] = prevX = x;
                    projected[i++] = prevY = y;
                }
            }
            if (i > 2)
                ll.addLine(projected, i, false);

            // trigger redraw to let renderer fetch the result.
            mMap.render();

            return true;
        }

        @Override
        public void cleanup(Task task) {
            task.bucket.clear();
        }

        private int addPoint(float[] points, int i, int x, int y) {
            points[i++] = x;
            points[i++] = y;
            return i;
        }

        /**
         * Ensure index size.
         *
         * @param size the size
         * @param copy the copy
         * @return the short[] array holding current index
         */
        int[] ensureIndexSize(int size, boolean copy) {
            if (size < index.length)
                return index;

            int[] newIndex = new int[size + GROW_INDICES];
            if (copy)
                System.arraycopy(index, 0, newIndex, 0, index.length);

            index = newIndex;

            return index;
        }
    }
}
