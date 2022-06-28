package iped.viewers.timelinegraph;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Iterator;

import org.jfree.chart.LegendItem;
import org.jfree.chart.LegendItemCollection;
import org.jfree.chart.axis.AxisSpace;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.event.PlotChangeEvent;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.Plot;
import org.jfree.chart.plot.PlotRenderingInfo;
import org.jfree.chart.plot.PlotState;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.chart.util.Args;
import org.jfree.data.xy.XYDataset;

public class IpedCombinedDomainXYPlot extends CombinedDomainXYPlot{
	
	boolean skipFireEvent=false;
	IpedLegendItemCollection legends;

	public IpedCombinedDomainXYPlot(DateAxis domainAxis) {
		super(domainAxis);
		legends = new IpedLegendItemCollection(this);

		
		//setDrawingSupplier(new IpedDrawingSupplier());
	}

    public IpedCombinedDomainXYPlot() {
		super();
		legends = new IpedLegendItemCollection(this);

		
		//setDrawingSupplier(new IpedDrawingSupplier());
	}

    @Override
	protected void fireChangeEvent() {
		if(!skipFireEvent) {
			super.fireChangeEvent();
		}
	}

    /**
     * Removes a subplot from the combined chart and sends a
     * {@link PlotChangeEvent} to all registered listeners.
     *
     * @param subplot  the subplot ({@code null} not permitted).
     */
    public void remove(XYPlot subplot) {
    	super.remove(subplot);
    }
    
	/**
     * Draws the plot within the specified area on a graphics device.
     *
     * @param g2  the graphics device.
     * @param area  the plot area (in Java2D space).
     * @param anchor  an anchor point in Java2D space ({@code null}
     *                permitted).
     * @param parentState  the state from the parent plot, if there is one
     *                     ({@code null} permitted).
     * @param info  collects chart drawing information ({@code null}
     *              permitted).
     */
    @Override
    public void draw(Graphics2D g2, Rectangle2D area, Point2D anchor,
            PlotState parentState, PlotRenderingInfo info) {
        // set up info collection...
        if (info != null) {
            info.setPlotArea(area);
        }

        // adjust the drawing area for plot insets (if any)...
        RectangleInsets insets = getInsets();
        insets.trim(area);

        setFixedRangeAxisSpaceForSubplots(null);
        AxisSpace space = calculateAxisSpace(g2, area);
        Rectangle2D dataArea = space.shrink(area, null);
        if (info != null) {
            info.setDataArea(dataArea);
        }

        super.draw(g2, dataArea, anchor, parentState, info);
    }

	public void setSkipFireEventChange(boolean b) {
		skipFireEvent=b;		
	}

    /**
     * Returns the legend items for the plot.  Each legend item is generated by
     * the plot's renderer, since the renderer is responsible for the visual
     * representation of the data.
     *
     * @return The legend items.
     */
    @Override
    public LegendItemCollection getLegendItems() {
    	return legends.getLegendItems();    	
    }

    public LegendItemCollection getLegendItems2() {
        if (this.getFixedLegendItems() != null) {
            return this.getFixedLegendItems();
        }
        LegendItemCollection result = new LegendItemCollection();
        
        XYItemRenderer renderer = getRenderer(0);
        XYPlot oldPlot = renderer.getPlot();

        for (int datasetIndex=0; datasetIndex<getDatasetCount(); datasetIndex++) {
        	XYDataset dataset = this.getDataset(datasetIndex);
            if (dataset == null) {
                continue;
            }
            renderer = getRenderer(datasetIndex);

            if (renderer == null) {
                renderer = getRenderer(0);
            }
            if (renderer != null) {
                int seriesCount = dataset.getSeriesCount();
                for (int i = 0; i < seriesCount; i++) {
                    if (renderer.isSeriesVisible(i)
                            && renderer.isSeriesVisibleInLegend(i)) {
                        renderer.setPlot((XYPlot)getSubplots().get(datasetIndex));
                        LegendItem item = renderer.getLegendItem(
                                0, i);
                        if (item != null) {
                            result.add(item);
                        }
                    }
                }
            }
        }

        renderer.setPlot(oldPlot);

        return result;
    }

	public void add(int pos, XYPlot subplot, XYDataset dataset) {
		super.add(subplot);
		
		setDataset(pos, dataset);
	}

	public int getGlobalSeriesIndex(int datasetIndex, int series) {
		int count=0;
        for (int i=0; i<getDatasetCount(); i++) {
        	XYDataset ds = getDataset(i);
        	if(i<datasetIndex) {
            	count+=ds.getSeriesCount();
        	}else {
        		return count+series;
        	}
        }
		return 0;
	}

	public IpedLegendItemCollection getLegends() {
		return legends;
	}

	public void setLegends(IpedLegendItemCollection legends) {
		this.legends = legends;
	}

	public void removeAllDataSets() {
		for(int i=0; i<getDatasetCount();i++) {
			setDataset(i, null);
		}
	}
}