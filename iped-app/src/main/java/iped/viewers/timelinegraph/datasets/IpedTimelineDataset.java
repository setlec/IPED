package iped.viewers.timelinegraph.datasets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;
import org.jfree.chart.util.Args;
import org.jfree.chart.util.PublicCloneable;
import org.jfree.data.DomainInfo;
import org.jfree.data.DomainOrder;
import org.jfree.data.Range;
import org.jfree.data.general.DatasetChangeEvent;
import org.jfree.data.time.TimePeriod;
import org.jfree.data.time.TimePeriodAnchor;
import org.jfree.data.time.TimeTableXYDataset;
import org.jfree.data.xy.AbstractIntervalXYDataset;
import org.jfree.data.xy.IntervalXYDataset;
import org.jfree.data.xy.TableXYDataset;
import org.jfree.data.xy.XYDomainInfo;

import iped.app.ui.CaseSearcherFilter;
import iped.app.ui.MetadataPanel.ValueCount;
import iped.data.IItemId;
import iped.properties.ExtraProperties;
import iped.search.IMultiSearchResult;
import iped.viewers.api.IMultiSearchResultProvider;
import iped.viewers.timelinegraph.IpedChartsPanel;

public class IpedTimelineDataset extends AbstractIntervalXYDataset
									implements Cloneable, PublicCloneable, IntervalXYDataset, DomainInfo, TimelineDataset,
									TableXYDataset, XYDomainInfo {
    IMultiSearchResultProvider resultsProvider;    
    CaseSearchFilterListenerFactory cacheFLFactory;
    
    /**
     * A flag that indicates that the domain is 'points in time'.  If this flag
     * is true, only the x-value (and not the x-interval) is used to determine
     * the range of values in the domain.
     */
    private boolean domainIsPointsInTime;

    /**
     * The point within each time period that is used for the X value when this
     * collection is used as an {@link org.jfree.data.xy.XYDataset}.  This can
     * be the start, middle or end of the time period.
     */
    private TimePeriodAnchor xPosition;

    /** A working calendar (to recycle) */
    private Calendar workingCalendar;

    Accumulator accumulator = new Accumulator();

    SortedSetDocValues timeEventGroupValues;
    IpedChartsPanel ipedChartsPanel;

    SortedSet<String> eventTypes = new TreeSet<String>();
    String[] eventTypesArray;
	LeafReader reader;
	
	static ThreadPoolExecutor queriesThreadPool =  new ThreadPoolExecutor(1, 20,
            20000, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>());
	
	static ThreadPoolExecutor slicesThreadPool =  new ThreadPoolExecutor(1, 20,
            20000, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>());

	class Count extends Number{
		int value=0;

		@Override
		public String toString() {
			return Integer.toString(value);
		}

		@Override
		public int intValue() {
			return value;
		}

		@Override
		public long longValue() {
			return value;
		}

		@Override
		public float floatValue() {
			return value;
		}

		@Override
		public double doubleValue() {
			return value;
		}
	}

	Date first;
	Date last;

	int itemCount=0;

	volatile int running=0;
	volatile int seriesCount=0;
	Object monitor = new Object();
	private String splitValue;

	public IpedTimelineDataset(IpedTimelineDatasetManager ipedTimelineDatasetManager, IMultiSearchResultProvider resultsProvider, CaseSearchFilterListenerFactory cacheFLFactory, String splitValue) throws Exception {
		this.ipedChartsPanel = ipedTimelineDatasetManager.ipedChartsPanel;
        Args.nullNotPermitted(ipedChartsPanel.getTimeZone(), "zone");
        Args.nullNotPermitted(ipedChartsPanel.getLocale(), "locale");
        this.workingCalendar = Calendar.getInstance(ipedChartsPanel.getTimeZone(), ipedChartsPanel.getLocale());
        //this.values = new DefaultKeyedValues2D(true);
        this.xPosition = TimePeriodAnchor.START;
		this.resultsProvider = resultsProvider;
		this.cacheFLFactory = cacheFLFactory;
		this.splitValue = splitValue;

        reader = resultsProvider.getIPEDSource().getLeafReader();

        timeEventGroupValues = reader.getSortedSetDocValues(ExtraProperties.TIME_EVENT_GROUPS);

		TermsEnum te = timeEventGroupValues.termsEnum();
		BytesRef br = te.next();

		List<CaseSearcherFilter> csfs = new ArrayList<CaseSearcherFilter>();
		while(br!=null) {
       		StringTokenizer st = new StringTokenizer(br.utf8ToString(), "|");
       		while(st.hasMoreTokens()) {
       			String eventType = st.nextToken().trim();
       			if(eventTypes.add(eventType)) {
    				//escape : char
					String eventField = ipedChartsPanel.getTimeEventColumnName(eventType);
					if(eventField==null) continue;

					running++;
    				String eventTypeEsc = eventField.replaceAll(":", "\\\\:");
    				eventTypeEsc = eventTypeEsc.replaceAll("/", "\\\\/");
    				eventTypeEsc = eventTypeEsc.replaceAll(" ", "\\\\ ");
    				eventTypeEsc = eventTypeEsc.replaceAll("-", "\\\\-");
    				
    				String queryText = eventTypeEsc+":[\"\" TO *]";
    				
    				if(ipedChartsPanel.getChartPanel().getSplitByCategory() && splitValue!=null) {
    					queryText+=" && category=\""+splitValue+"\"";
    				}

       				CaseSearcherFilter csf = new CaseSearcherFilter(queryText);
       				csf.getSearcher().setNoScoring(true);
       				csf.applyUIQueryFilters();

       				IpedTimelineDataset self = this;

       				csf.addCaseSearchFilterListener(cacheFLFactory.getCaseSearchFilterListener(eventType, csf, this, splitValue));

       				IMultiSearchResult timelineSearchResults;
					csf.applyUIQueryFilters();
					csfs.add(csf);
       			}
       		}
       		br = te.next();
		}

		eventTypesArray=new String[running];
		
		for(CaseSearcherFilter csf:csfs) {
			csf.setThreadPool(slicesThreadPool);
			queriesThreadPool.execute(csf);
		}
		
		try {
			synchronized (monitor) {
				monitor.wait();				
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
    }

	/**
     * Returns a flag that controls whether the domain is treated as 'points in
     * time'.
     * <P>
     * This flag is used when determining the max and min values for the domain.
     * If true, then only the x-values are considered for the max and min
     * values.  If false, then the start and end x-values will also be taken
     * into consideration.
     *
     * @return The flag.
     *
     * @see #setDomainIsPointsInTime(boolean)
     */
    public boolean getDomainIsPointsInTime() {
        return this.domainIsPointsInTime;
    }

    /**
     * Sets a flag that controls whether the domain is treated as 'points in
     * time', or time periods.  A {@link DatasetChangeEvent} is sent to all
     * registered listeners.
     *
     * @param flag  the new value of the flag.
     *
     * @see #getDomainIsPointsInTime()
     */
    public void setDomainIsPointsInTime(boolean flag) {
        this.domainIsPointsInTime = flag;
        notifyListeners(new DatasetChangeEvent(this, this));
    }

    /**
     * Returns the position within each time period that is used for the X
     * value.
     *
     * @return The anchor position (never {@code null}).
     *
     * @see #setXPosition(TimePeriodAnchor)
     */
    public TimePeriodAnchor getXPosition() {
        return this.xPosition;
    }

    /**
     * Sets the position within each time period that is used for the X values,
     * then sends a {@link DatasetChangeEvent} to all registered listeners.
     *
     * @param anchor  the anchor position ({@code null} not permitted).
     *
     * @see #getXPosition()
     */
    public void setXPosition(TimePeriodAnchor anchor) {
        Args.nullNotPermitted(anchor, "anchor");
        this.xPosition = anchor;
        notifyListeners(new DatasetChangeEvent(this, this));
    }

    /**
     * Returns the number of items in ALL series.
     *
     * @return The item count.
     */
    @Override
    public int getItemCount() {
        return accumulator.rowTimestamps.size();
    }
    

    @Override
    public DomainOrder getDomainOrder() {
    	return DomainOrder.ASCENDING;
    }    

    /**
     * Returns the number of items in a series.  This is the same value
     * that is returned by {@link #getItemCount()} since all series
     * share the same x-values (time periods).
     *
     * @param series  the series (zero-based index, ignored).
     *
     * @return The number of items within the series.
     */
    @Override
    public int getItemCount(int series) {
        return accumulator.rowTimestamps.size();
    }

    /**
     * Returns the number of series in the dataset.
     *
     * @return The series count.
     */
    @Override
    public int getSeriesCount() {
        return accumulator.colEvents.size();
    }

    /**
     * Returns the key for a series.
     *
     * @param series  the series (zero-based index).
     *
     * @return The key for the series.
     */
    @Override
    public Comparable getSeriesKey(int series) {
        return accumulator.colEvents.get(series);
    }

    /**
     * Returns the x-value for an item within a series.  The x-values may or
     * may not be returned in ascending order, that is up to the class
     * implementing the interface.
     *
     * @param series  the series (zero-based index).
     * @param item  the item (zero-based index).
     *
     * @return The x-value.
     */
    @Override
    public Number getX(int series, int item) {
        return getXValue(series, item);
    }

    /**
     * Returns the x-value (as a double primitive) for an item within a series.
     *
     * @param series  the series index (zero-based).
     * @param item  the item index (zero-based).
     *
     * @return The value.
     */
    @Override
    public double getXValue(int series, int item) {
        TimePeriod period = (TimePeriod) accumulator.rowTimestamps.get(item);
        return getXValue(period);
    }

    /**
     * Returns the starting X value for the specified series and item.
     *
     * @param series  the series (zero-based index).
     * @param item  the item within a series (zero-based index).
     *
     * @return The starting X value for the specified series and item.
     *
     * @see #getStartXValue(int, int)
     */
    @Override
    public Number getStartX(int series, int item) {
        return getStartXValue(series, item);
    }

    /**
     * Returns the start x-value (as a double primitive) for an item within
     * a series.
     *
     * @param series  the series index (zero-based).
     * @param item  the item index (zero-based).
     *
     * @return The value.
     */
    @Override
    public double getStartXValue(int series, int item) {
        TimePeriod period = (TimePeriod) accumulator.rowTimestamps.get(item);
        return period.getStart().getTime();
    }

    /**
     * Returns the ending X value for the specified series and item.
     *
     * @param series  the series (zero-based index).
     * @param item  the item within a series (zero-based index).
     *
     * @return The ending X value for the specified series and item.
     *
     * @see #getEndXValue(int, int)
     */
    @Override
    public Number getEndX(int series, int item) {
        return getEndXValue(series, item);
    }

    /**
     * Returns the end x-value (as a double primitive) for an item within
     * a series.
     *
     * @param series  the series index (zero-based).
     * @param item  the item index (zero-based).
     *
     * @return The value.
     */
    @Override
    public double getEndXValue(int series, int item) {
        TimePeriod period = (TimePeriod) accumulator.rowTimestamps.get(item);
        return period.getEnd().getTime();
    }

    /**
     * Returns the y-value for an item within a series.
     *
     * @param series  the series (zero-based index).
     * @param item  the item (zero-based index).
     *
     * @return The y-value (possibly {@code null}).
     */
    @Override
    public Number getY(int series, int item) {
    	HashMap<Integer, Count> hc = accumulator.counts.get(item);    	
        return hc.get(series);
    }


    /**
     * Returns the starting Y value for the specified series and item.
     *
     * @param series  the series (zero-based index).
     * @param item  the item within a series (zero-based index).
     *
     * @return The starting Y value for the specified series and item.
     */
    @Override
    public Number getStartY(int series, int item) {
        return getY(series, item);
    }

    /**
     * Returns the ending Y value for the specified series and item.
     *
     * @param series  the series (zero-based index).
     * @param item  the item within a series (zero-based index).
     *
     * @return The ending Y value for the specified series and item.
     */
    @Override
    public Number getEndY(int series, int item) {
        return getY(series, item);
    }

    /**
     * Returns the x-value for a time period.
     *
     * @param period  the time period.
     *
     * @return The x-value.
     */
    private long getXValue(TimePeriod period) {
        long result = 0L;
        if (this.xPosition == TimePeriodAnchor.START) {
            result = period.getStart().getTime();
        }
        else if (this.xPosition == TimePeriodAnchor.MIDDLE) {
            long t0 = period.getStart().getTime();
            long t1 = period.getEnd().getTime();
            result = t0 + (t1 - t0) / 2L;
        }
        else if (this.xPosition == TimePeriodAnchor.END) {
            result = period.getEnd().getTime();
        }
        return result;
    }


    /**
     * Returns the minimum x-value in the dataset.
     *
     * @param includeInterval  a flag that determines whether or not the
     *                         x-interval is taken into account.
     *
     * @return The minimum value.
     */
    @Override
    public double getDomainLowerBound(boolean includeInterval) {
        double result = Double.NaN;
        Range r = getDomainBounds(includeInterval);
        if (r != null) {
            result = r.getLowerBound();
        }
        return result;
    }

    /**
     * Returns the maximum x-value in the dataset.
     *
     * @param includeInterval  a flag that determines whether or not the
     *                         x-interval is taken into account.
     *
     * @return The maximum value.
     */
    @Override
    public double getDomainUpperBound(boolean includeInterval) {
        double result = Double.NaN;
        Range r = getDomainBounds(includeInterval);
        if (r != null) {
            result = r.getUpperBound();
        }
        return result;
    }


    /**
     * Returns the range of the values in this dataset's domain.
     *
     * @param includeInterval  a flag that controls whether or not the
     *                         x-intervals are taken into account.
     *
     * @return The range.
     */
    @Override
    public Range getDomainBounds(boolean includeInterval) {
        List keys = accumulator.rowTimestamps;
        if (keys.isEmpty()) {
            return null;
        }

        TimePeriod first = (TimePeriod) keys.get(0);
        TimePeriod last = (TimePeriod) keys.get(keys.size() - 1);

        if (!includeInterval || this.domainIsPointsInTime) {
            return new Range(getXValue(first), getXValue(last));
        }
        else {
            return new Range(first.getStart().getTime(),
                    last.getEnd().getTime());
        }
    }

    /**
     * Tests this dataset for equality with an arbitrary object.
     *
     * @param obj  the object ({@code null} permitted).
     *
     * @return A boolean.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof TimeTableXYDataset)) {
            return false;
        }
        IpedTimelineDataset that = (IpedTimelineDataset) obj;
        if (this.domainIsPointsInTime != that.domainIsPointsInTime) {
            return false;
        }
        if (this.xPosition != that.xPosition) {
            return false;
        }
        if (!this.workingCalendar.getTimeZone().equals(
            that.workingCalendar.getTimeZone())
        ) {
            return false;
        }
        if (!this.accumulator.counts.equals(that.accumulator.counts)) {
            return false;
        }
        return true;
    }

    /**
     * Returns a clone of this dataset.
     *
     * @return A clone.
     *
     * @throws CloneNotSupportedException if the dataset cannot be cloned.
     */
    @Override
    public Object clone() throws CloneNotSupportedException {
    	IpedTimelineDataset clone = (IpedTimelineDataset) super.clone();
        //clone.values = (DefaultKeyedValues2D) this.values.clone();
        clone.workingCalendar = (Calendar) this.workingCalendar.clone();
        return clone;
    }
	
    HashMap<String, HashMap<String, List<IItemId>>> itemIdsMap = new HashMap<String, HashMap<String, List<IItemId>>>(); 

    public List<IItemId> getItems(int item, int seriesId) {
    	HashMap<String, List<IItemId>> series = this.itemIdsMap.get(accumulator.rowTimestamps.get(item));
    	if(series!=null) {
    		return series.get(this.getSeriesKey(seriesId));
    	}
    	return null;
    }

	public void addDocIds(TimePeriod t, String eventField, ArrayList<IItemId> docIds) {
		HashMap<String, List<IItemId>> series = this.itemIdsMap.get(t.toString());
		if(series==null) {
			series = new HashMap<String, List<IItemId>>();
			this.itemIdsMap.put(t.toString(), series);
		}else {
			List<IItemId> ids = series.get(eventField);
			if(ids!=null) {
				synchronized (docIds) {
					ids.addAll(docIds);
				}
			}else {
				series.put(eventField,docIds);
			}
		}

		series.put(eventField, docIds);
	}

	public void addValue(Count count, TimePeriod t, String eventType, ArrayList<IItemId> docIds) {
		accumulator.addValue(count, t, eventType, docIds);
	}

	public void addValue(ValueCount valueCount, ArrayList<IItemId> docIds, String eventType) {
		accumulator.addValue(valueCount, docIds, eventType);
	}

	@Override
	public Range getDomainBounds(List visibleSeriesKeys, boolean includeInterval) {		
		return new Range(accumulator.min.getStart().getTime(), accumulator.max.getEnd().getTime());
	}
	
    public class Accumulator{
    	TimePeriod min;
    	TimePeriod max;
    	ArrayList<String> colEvents = new ArrayList<String>();
		private ArrayList<TimePeriod> rowTimestamps = new ArrayList<TimePeriod>();
		private ArrayList<HashMap<Integer, Count>> counts = new ArrayList<HashMap<Integer, Count>>();
		private HashMap<String, HashMap<String, List<IItemId>>> itemIdsMap = new HashMap<String, HashMap<String, List<IItemId>>>(); 
    	
    	public Accumulator() {
    	}

    	public void addDocIds(TimePeriod t, String eventField, ArrayList<IItemId> docIds) {
    		HashMap<String, List<IItemId>> series = this.itemIdsMap.get(t);
    		if(series==null) {
    			series = new HashMap<String, List<IItemId>>();
    			this.itemIdsMap.put(t.toString(), series);
    		}else {
    			List<IItemId> ids = series.get(eventField);
    			if(ids!=null) {
    				synchronized (docIds) {
    					ids.addAll(docIds);
    				}
    			}else {
    				series.put(eventField,docIds);
    			}
    		}

    		series.put(eventField, docIds);
    	}

    	public void addValue(ValueCount valueCount, ArrayList<IItemId> docIds, String eventType) {
    		Date d = ipedChartsPanel.getDomainAxis().ISO8601DateParse(valueCount.getVal());
    		TimePeriod t = ipedChartsPanel.getDomainAxis().getDateOnConfiguredTimePeriod(ipedChartsPanel.getTimePeriodClass(), d);
    		
    		if(t!=null) {
        		addDocIds(t, eventType, docIds);

        		if(min==null || t.getStart().before(min.getStart())) {
        			min=t;
        		}

        		if(max==null || t.getEnd().after(max.getEnd())) {
        			max=t;
        		}

        		int col = colEvents.indexOf(eventType);
        		if(col==-1) {
        			colEvents.add(eventType);
        			col=colEvents.size()-1;
        		}

        		int row = rowTimestamps.indexOf(t);
        		if(row==-1) {
        			rowTimestamps.add(t);
        			row=rowTimestamps.size()-1;

        			Count c=new Count();
        			c.value=valueCount.getCount();
        			HashMap<Integer,Count> values=new HashMap<Integer,Count>();
        			values.put(col,c);
        			counts.add(values);
        			return;
        		}

        		Count c;

        		HashMap<Integer,Count> values = counts.get(row);
        		c = values.get(col);
        		if(c==null) {
        			c=new Count();
        			values.put(col, c);
        		}
        		c.value+=valueCount.getCount();
    		}else {
    			System.out.println("Unexpected null value after string parsing:"+ d+ "  :  "+ valueCount.getVal());
    		}
    	}

    	public void addValue(Count count, TimePeriod t, String eventType, ArrayList<IItemId> docIds) {
    		if(min==null || t.getStart().before(min.getStart())) {
    			min=t;
    		}

    		if(max==null || t.getEnd().after(max.getEnd())) {
    			max=t;
    		}

    		addDocIds(t, eventType, docIds);

    		int col = colEvents.indexOf(eventType);
    		if(col==-1) {
    			colEvents.add(eventType);
    			col=colEvents.size()-1;
    		}

    		int row = rowTimestamps.indexOf(t);
    		if(row==-1) {
    			rowTimestamps.add(t);
    			row=rowTimestamps.size()-1;

    			HashMap<Integer,Count> values=new HashMap<Integer,Count>();
    			values.put(col,count);
    			counts.add(values);
    			return;
    		}

    		Count c;

    		HashMap<Integer,Count> values = counts.get(row);
    		c = values.get(col);
    		if(c==null) {
    			c=count;
    			values.put(col, c);
    		}else {
    			c.value+=count.value;
    		}
    	}
    	
    	synchronized void merge(Accumulator acc) {
    		if(acc.colEvents.size()>0) {
        		int col = this.colEvents.indexOf(acc.colEvents.get(0));
        		if(col<0) {
            		this.colEvents.add(acc.colEvents.get(0));
            		col=this.colEvents.size()-1;
        		}
        		this.itemIdsMap.putAll(acc.itemIdsMap);

        		for(int i=0; i<acc.rowTimestamps.size(); i++) {
        			TimePeriod t = acc.rowTimestamps.get(i);
        			int index = this.rowTimestamps.indexOf(t);
        			if(index<0) {
                		this.rowTimestamps.add(t);
                		this.counts.add(acc.counts.get(i));
        			}else {
        				HashMap<Integer, Count> values = this.counts.get(index);
        				HashMap<Integer, Count> accValues = acc.counts.get(i);
        				Count c = values.get(col);
        				if(c==null) {
    						values.put(col, accValues.get(0));
        				}else {
        					c.value+=accValues.get(0).value;
        				}
        			}
        		}
        		
        		if(this.min==null || acc.min.getStart().before(this.min.getStart())) {
        			this.min = acc.min;
        		}
        		if(this.max==null || acc.max.getEnd().after(this.max.getEnd())) {
        			this.max = acc.max;
        		}
    		}
    	}
    }
	
}
