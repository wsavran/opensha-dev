package scratch.kevin.simulators.ruptures.rotation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.descriptive.moment.Variance;
import org.apache.commons.math3.util.MathArrays;
import org.opensha.commons.data.Site;
import org.opensha.commons.data.function.DefaultXY_DataSet;
import org.opensha.commons.data.function.XY_DataSet;
import org.opensha.commons.geo.Location;
import org.opensha.commons.geo.LocationUtils;
import org.opensha.commons.util.DataUtils;
import org.opensha.commons.util.ExceptionUtils;
import org.opensha.commons.util.IDPairing;
import org.opensha.commons.util.MarkdownUtils;
import org.opensha.commons.util.MarkdownUtils.TableBuilder;
import org.opensha.sha.earthquake.EqkRupture;
import org.opensha.sha.faultSurface.FaultTrace;
import org.opensha.sha.faultSurface.QuadSurface;
import org.opensha.sha.imr.mod.impl.BaylessSomerville2013DirectivityModifier;
import org.opensha.sha.simulators.EventRecord;
import org.opensha.sha.simulators.RSQSimEvent;
import org.opensha.sha.simulators.SimulatorElement;
import org.opensha.sha.simulators.utils.RSQSimSubSectEqkRupture;
import org.opensha.sha.simulators.utils.RSQSimSubSectionMapper;
import org.opensha.sha.simulators.utils.RSQSimUtils;
import org.opensha.sha.simulators.utils.RSQSimSubSectionMapper.SlipAlongSectAlgorithm;
import org.opensha.sha.simulators.utils.RSQSimSubSectionMapper.SubSectionMapping;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.primitives.Doubles;

import scratch.kevin.simCompare.SimulationRotDProvider;
import scratch.kevin.simulators.RSQSimCatalog;
import scratch.kevin.simulators.ruptures.BBP_PartBValidationConfig;
import scratch.kevin.simulators.ruptures.BBP_PartBValidationConfig.FilterMethod;
import scratch.kevin.simulators.ruptures.rotation.RotatedRupVariabilityConfig.Quantity;
import scratch.kevin.simulators.ruptures.rotation.RotatedRupVariabilityConfig.RotationSpec;

public abstract class RSQSimRotatedRupVariabilityPageGen extends RotatedRupVariabilityPageGen<RSQSimEvent> {

	private RSQSimCatalog catalog;
	private Map<Integer, RSQSimEvent> eventsMap;

	public RSQSimRotatedRupVariabilityPageGen(RSQSimCatalog catalog, RotatedRupVariabilityConfig<RSQSimEvent> config,
			FilterMethod filter, double mag, SimulationRotDProvider<RotationSpec> prov, double[] calcPeriods) {
		super(config, filter, mag, prov, calcPeriods);
		this.catalog = catalog;
	}
	
	public RSQSimRotatedRupVariabilityPageGen(RSQSimCatalog catalog,
			FilterMethod filter, Map<Double, ? extends RotatedRupVariabilityConfig<RSQSimEvent>> magConfigs,
			Map<Double, SimulationRotDProvider<RotationSpec>> magProvs,
			double[] calcPeriods) {
		super(filter, magConfigs, magProvs, calcPeriods);
		this.catalog = catalog;
	}
	
	@Override
	protected String getModelName() {
		return catalog.getName();
	}
	
	@Override
	protected RSQSimSubSectEqkRupture buildGMPE_Rupture(RSQSimEvent event) {
		return catalog.getMappedSubSectRupture(event);
	}
	
	@Override
	protected synchronized RSQSimEvent getEvent(int eventID) {
		if (eventsMap == null) {
			try {
				eventsMap = loadEvents(catalog, getAllEventIDs());
			} catch (IOException e) {
				throw ExceptionUtils.asRuntimeException(e);
			}
		}
		return eventsMap.get(eventID);
	}
	
	protected void setEventsMap(Map<Integer, RSQSimEvent> eventsMap) {
		this.eventsMap = eventsMap;
	}
	
	private Map<IDPairing, Double> elemDistCache = new HashMap<>();
	
	@Override
	protected double calcVprop(RSQSimEvent event) {
		double minTime = Double.POSITIVE_INFINITY;
		SimulatorElement hypo = null;
		for (EventRecord rec : event) {
			double[] times = rec.getElementTimeFirstSlips();
			Preconditions.checkNotNull(times, "Event doesn't have timing information");
			List<SimulatorElement> elems = rec.getElements();
			
			for (int i=0; i<elems.size(); i++) {
				if (times[i] < minTime) {
					minTime = times[i];
					hypo = elems.get(i);
				}
			}
		}

		int hypoID = hypo.getID();
		List<Double> vels = new ArrayList<>();
		for (EventRecord rec : event) {
			double[] times = rec.getElementTimeFirstSlips();
			Preconditions.checkNotNull(times, "Event doesn't have timing information");
			List<SimulatorElement> elems = rec.getElements();
			
			for (int i=0; i<elems.size(); i++) {
				SimulatorElement elem = elems.get(i);
				if (elem.getID() == hypoID)
					continue;
				int elemID = elem.getID();
				IDPairing pair = hypoID > elemID ? new IDPairing(elemID, hypoID) : new IDPairing(hypoID, elemID);
				Double dist = elemDistCache.get(pair);
				if (dist == null) {
					dist = LocationUtils.linearDistanceFast(hypo.getCenterLocation(), elem.getCenterLocation());
					elemDistCache.put(pair, dist);
				}
				double tDelta = times[i] - minTime;
				if (tDelta == 0)
					continue;
				double vel = dist/(tDelta);
				vels.add(vel);
			}
			
		}
		
		return DataUtils.median(Doubles.toArray(vels));
	}
	
	protected double getMag(RSQSimEvent event) {
		return event.getMagnitude();
	}
	
	protected double getArea(RSQSimEvent event) {
		return event.getArea();
	}
	
	protected double getMaxSlip(RSQSimEvent event) {
		return StatUtils.max(event.getAllElementSlips());
	}
	
	protected double getMeanSlip(RSQSimEvent event) {
		ArrayList<SimulatorElement> elems = event.getAllElements();
		double[] slips = event.getAllElementSlips();
		double mean = 0d;
		double totArea = 0d;
		for (int i=0; i<slips.length; i++) {
			double area = elems.get(i).getArea();
			mean += area*slips[i];
			totArea += area;
		}
		mean /= totArea;
		return mean;
	}
	
	protected double getSlipStdDev(RSQSimEvent event) {
		ArrayList<SimulatorElement> elems = event.getAllElements();
		double[] slips = event.getAllElementSlips();
		double[] weights = new double[slips.length];
		for (int i=0; i<slips.length; i++)
			weights[i] = elems.get(i).getArea();
		double var = new Variance().evaluate(slips, MathArrays.normalizeArray(weights, weights.length));
		return Math.sqrt(var);
	}
	
	protected double getMeanMidSeisSlip(RSQSimEvent event) {
		RSQSimSubSectionMapper mapper;
		try {
			mapper = catalog.getSubSectMapper();
			mapper.trackSlipOnSections();
		} catch (IOException e) {
			throw ExceptionUtils.asRuntimeException(e);
		}
		SlipAlongSectAlgorithm slipAlg = SlipAlongSectAlgorithm.MID_SEIS_SLIPPED_LEN;
		List<List<SubSectionMapping>> mappings = mapper.getAllSubSectionMappings(event);
		
		double areaWeightedSlip = 0d;
		double sumArea = 0d;
		for (List<SubSectionMapping> bundle : mappings) {
			for (SubSectionMapping mapping : bundle) {
				double area = mapping.getAreaForAverageSlip(slipAlg);
				double slip = mapping.getAverageSlip(slipAlg);
				areaWeightedSlip += slip*area;
				sumArea += area;
			}
		}
		return areaWeightedSlip == 0 ? 0 : areaWeightedSlip / sumArea;
	}
	
	@Override
	protected void plotExample(File resourcesDir, String prefix, double distance, List<Quantity> variedQuantities)
			throws IOException {
		
		Double minMag = Double.POSITIVE_INFINITY;
		for (Double mag : magEventIDs.keySet())
			minMag = Double.min(minMag, mag);
		
		// choose event with the least difference between Rrup and Rjb
		double minAbsDistDiff = Double.POSITIVE_INFINITY;
		RSQSimEvent exampleRupture = null;
		for (Integer eventID : magEventIDs.get(minMag)) {
			RSQSimEvent event = getEvent(eventID);
			Location centroid = RuptureRotationUtils.calcRuptureCentroid(event);
			event = RuptureRotationUtils.getInitiallyOriented( catalog, event, centroid);
			Location testLoc = LocationUtils.location(centroid, Math.PI, 100d);
			double rRup = RuptureRotationUtils.calcMinDist(testLoc, event, false);
			double rJB = RuptureRotationUtils.calcMinDist(testLoc, event, true);
			double diff = Math.abs(rRup - rJB);
//			System.out.println("Testing event "+eventID+": rJB="+(float)rJB+", rRup="+(float)rRup+", diff="+(float)diff);
			if (diff < minAbsDistDiff) {
				// check that the hypcoenter isn't on top of the centroid
				Location hypo = RSQSimUtils.getHypocenter(event);
				double dist = LocationUtils.horzDistance(centroid, hypo);
				if (dist > 5d) {
					exampleRupture = event;
					minAbsDistDiff = diff;
				}
			}
		}
//		System.out.println("Went with "+exampleRupture.getID()+" with diff="+(float)minAbsDistDiff);
		
//		RSQSimEvent exampleRupture = getEvent(magEventIDs.get(minMag).get(0));
		List<Site> sites = new ArrayList<>();
		sites.add(this.sites.get(0));
		List<RSQSimEvent> ruptures = new ArrayList<>();
		ruptures.add(exampleRupture);
		int numSourceAz = variedQuantities.contains(Quantity.SOURCE_AZIMUTH) ? numExampleRotations : 1;
		int numSiteToSourceAz = variedQuantities.contains(Quantity.SITE_TO_SOURTH_AZIMUTH) ? numExampleRotations : 1;
		RSQSimRotatedRupVariabilityConfig config = new RSQSimRotatedRupVariabilityConfig(catalog, sites, ruptures, new double[] {distance},
				numSourceAz, numSiteToSourceAz);
		
		config.plotRotations(resourcesDir, prefix, config.getRotations(), true);
	}
	
	@Override
	protected Table<Float, Double, XY_DataSet> calcDirectivityComparisons(Double mag, double[] periods,
			Site[] sites, File resourcesDir) {
		RSQSimRotatedRupVariabilityConfig config = (RSQSimRotatedRupVariabilityConfig)magConfigs.get(mag);
		if (!config.hasRuptures()) {
			if (eventsMap == null) {
				try {
					eventsMap = loadEvents(catalog, getAllEventIDs());
				} catch (IOException e) {
					throw ExceptionUtils.asRuntimeException(e);
				}
			}
			config.setRuptures(eventsMap.values());
		}
		
		List<Integer> eventIDs = config.getValues(Integer.class, Quantity.EVENT_ID);
		
		int[] periodIndexes = new int[periods.length];
		for (int i=0; i<periods.length; i++)
			periodIndexes[i] = Doubles.indexOf(calcPeriods, periods[i]);
		
		BaylessSomerville2013DirectivityModifier bs = new BaylessSomerville2013DirectivityModifier();
		
		Table<Float, Double, XY_DataSet> distPeriodXYs = HashBasedTable.create();
		
		TableBuilder table = null;
		File dirDir = null;
		if (DIRECTIVITY_DEBUG) {
			table = MarkdownUtils.tableBuilder();
			table.initNewLine();
			for (double period : getDirectivityPlotPeriods(periods))
				table.addColumn(optionalDigitDF.format(period)+"s Map Plot");
			table.addColumn("Residuals Plot");
			table.finalizeLine();
			dirDir = new File(resourcesDir, DIRECTIVITY_DEBUG_DIRNAME);
		}
		
		try {
			for (int i=0; i<eventIDs.size(); i++) {
				// build simple rupture description
				int eventID = eventIDs.get(i);
				RSQSimEvent event = getEvent(eventID);
				
				// rotated such that strike is zero, e.g. oriented facing north
				// aki & richards, dips to right (if dips)
				RSQSimEvent oriented = config.getInitiallyOrientedRupture(eventID);
				
				Location centroid = config.getCentroid(event);
				
				QuadSurface qSurf = RuptureRotationUtils.getIdealizedQuadSurfaceRepresentation(oriented, centroid);
				
				DistanceOverrideQuadSurface surf = new DistanceOverrideQuadSurface(qSurf, oriented.getAllElements());
				
				Location hypo = RSQSimUtils.getHypocenter(oriented);
				boolean dipping = qSurf.getAveDip() < 80;
				double aveRake = dipping ? 90 : 180;
				EqkRupture rup = new EqkRupture(mag, aveRake, surf, hypo);

				Table<Float, Float, Location> azDistLocMap = HashBasedTable.create();
				Table<Float, Float, double[]> azDistFdMap = HashBasedTable.create();
				for (Float sourceAz : config.getValues(Float.class, Quantity.SOURCE_AZIMUTH)) {
					float siteAz = 180 - sourceAz;
					for (Float distance : distances) {
						Location siteLoc = LocationUtils.location(centroid, Math.toRadians(siteAz), distance);
						// correct distance
						for (int j=0; j<3; j++) {
							double minDist = RuptureRotationUtils.calcMinDist(
									siteLoc, oriented, BBP_PartBValidationConfig.DIST_JB);
							// positive means we're too close and need to move further
							double distDiff = distance - minDist;
							siteLoc = LocationUtils.location(siteLoc, Math.toRadians(siteAz), distDiff);
						}
						
						surf.distsMap.put(siteLoc, distance.doubleValue());
						
						double[] fds = new double[periods.length];
						for (int p=0; p<periods.length; p++)
							fds[p] = bs.getFd(rup, siteLoc, periods[p]);
						
						azDistFdMap.put(sourceAz, distance, fds);
						azDistLocMap.put(sourceAz, distance, siteLoc);
					}
				}
				
				Map<RotationSpec, double[]> diffsMap = new HashMap<>();
				
				for (Float distance : distances) {
					EventTerm eventTerm = eventTermCache.get(
							new EventTermKey(eventID, mag, distance, sites));
					
					List<RotationSpec> rotations = eventTerm.rotations;
					for (int j=0; j<rotations.size(); j++) {
						RotationSpec rot = rotations.get(j);
						
						float sourceAz = nullAsZero(rot.sourceAz);
						
						double[] fds = azDistFdMap.get(sourceAz, distance);
						
						double[] diffs = new double[periods.length];
						
						for (int p=0; p<periods.length; p++) {
							double meanVal = eventTerm.eventTerms[periodIndexes[p]];
							double value = eventTerm.rotLogVals.get(j)[periodIndexes[p]];
							diffs[p] = value - meanVal;
							
							XY_DataSet xy = distPeriodXYs.get(distance, periods[p]);
							if (xy == null) {
								xy = new DefaultXY_DataSet();
								distPeriodXYs.put(distance, periods[p], xy);
							}
							xy.set(fds[p], diffs[p]);
						}
						diffsMap.put(rot, diffs);
					}
					
				}
				if (DIRECTIVITY_DEBUG) {
					File[] files = plotIndvRupDirectivity(eventID, oriented, rup, azDistFdMap, azDistLocMap,
							diffsMap, periods, dirDir);
					table.initNewLine();
					for (File file : files)
						table.addColumn("![plot]("+file.getName()+")");
					table.finalizeLine();
				}
			}
		} catch (Exception e) {
			throw ExceptionUtils.asRuntimeException(e);
		}
		
		if (DIRECTIVITY_DEBUG) {
			List<String> lines = new ArrayList<>();
			lines.add("# Directivity Debug");
			lines.add("");
			lines.add("Map plots show the re-oriented (with strike=0) raw rupture in black, "
					+ "the linear GMPE source used to predict directivity with a dashed line, "
					+ "and hypocenter with a star. Map background is empirical fD estimates, and mean "
					+ "simulated values at 20 and 50 km are overlaid. The chart on the right shows "
					+ "the simulated values for 20 km at each spectral period.");
			lines.add("");
			lines.addAll(table.build());
			lines.add("");
			File outputDir = new File(resourcesDir, DIRECTIVITY_DEBUG_DIRNAME);
			try {
				MarkdownUtils.writeReadmeAndHTML(lines, outputDir);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		return distPeriodXYs;
	}
	
	private class DistanceOverrideQuadSurface extends QuadSurface {
		
		private Map<Location, Double> distsMap = new HashMap<>();
		private List<SimulatorElement> elems;

		public DistanceOverrideQuadSurface(QuadSurface surf,
				List<SimulatorElement> elems) {
			super(surf.getUpperEdge(), surf.getAveDip(), surf.getAveWidth());
			this.elems = elems;
		}

		@Override
		public double getDistanceRup(Location loc) {
			if (distsMap.containsKey(loc))
				return distsMap.get(loc);
			double dist = Double.POSITIVE_INFINITY;
			for (SimulatorElement elem : elems)
				for (Location v : elem.getVertices())
					dist = Math.min(dist, LocationUtils.linearDistanceFast(v, loc));
			distsMap.put(loc, dist);
			return dist;
		}
		
	}
	

}
