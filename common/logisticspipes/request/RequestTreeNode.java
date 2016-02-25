package logisticspipes.request;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.SortedSet;
import java.util.TreeSet;

import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.ICraft;
import logisticspipes.interfaces.routing.IFilter;
import logisticspipes.interfaces.routing.IProvide;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.request.RequestTree.ActiveRequestType;
import logisticspipes.request.RequestTree.workWeightedSorter;
import logisticspipes.request.resources.IResource;
import logisticspipes.routing.ExitRoute;
import logisticspipes.routing.IRouter;
import logisticspipes.routing.PipeRoutingConnectionType;
import logisticspipes.routing.ServerRouter;
import logisticspipes.routing.order.IOrderInfoProvider;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.routing.order.LinkedLogisticsOrderList;
import logisticspipes.utils.tuples.Pair;

import lombok.Getter;

public class RequestTreeNode {

	protected RequestTreeNode(IResource requestType, RequestTreeNode parentNode, EnumSet<ActiveRequestType> requestFlags, IAdditionalTargetInformation info) {
		this(null, requestType, parentNode, requestFlags, info);
	}

	private RequestTreeNode(ICraftingTemplate template, IResource requestType, RequestTreeNode parentNode, EnumSet<ActiveRequestType> requestFlags, IAdditionalTargetInformation info) {
		this.info = info;
		this.parentNode = parentNode;
		this.requestType = requestType;
		if (parentNode != null) {
			parentNode.subRequests.add(this);
			root = parentNode.root;
		} else {
			root = (RequestTree) this;
		}
		if (template != null) {
			declareCrafterUsed(template);
		}

		if (requestFlags.contains(ActiveRequestType.Provide) && checkProvider()) {
			return;
		}

		if (requestFlags.contains(ActiveRequestType.Craft) && checkExtras()) {
			return;// crafting was able to complete
		}

		if (requestFlags.contains(ActiveRequestType.Craft) && checkCrafting()) {
			return;// crafting was able to complete
		}

		// crafting is not done!
	}

	@Getter
	private final IResource requestType;

	private final IAdditionalTargetInformation info;
	private final RequestTreeNode parentNode;
	protected final RequestTree root;
	private List<RequestTreeNode> subRequests = new ArrayList<RequestTreeNode>();
	private List<IPromise> promises = new ArrayList<IPromise>();
	private List<IExtraPromise> extrapromises = new ArrayList<IExtraPromise>();
	private List<IExtraPromise> byproducts = new ArrayList<IExtraPromise>();
	private SortedSet<ICraftingTemplate> usedCrafters = new TreeSet<ICraftingTemplate>();
	private ICraftingTemplate lastCrafterTried = null;

	private int promiseAmount = 0;

	private boolean isCrafterUsed(ICraftingTemplate test) {
		if (!usedCrafters.isEmpty() && usedCrafters.contains(test)) {
			return true;
		}
		if (parentNode == null) {
			return false;
		}
		return parentNode.isCrafterUsed(test);
	}

	// returns false if the crafter was already on the list.
	private boolean declareCrafterUsed(ICraftingTemplate test) {
		if (isCrafterUsed(test)) {
			return false;
		}
		usedCrafters.add(test);
		return true;
	}

	public int getPromiseAmount() {
		return promiseAmount;
	}

	public int getMissingAmount() {
		return requestType.getRequestedAmount() - promiseAmount;
	}

	public void addPromise(IPromise promise) {
		if (!promise.matches(requestType)) {
			throw new IllegalArgumentException("wrong item");
		}
		if (getMissingAmount() == 0) {
			throw new IllegalArgumentException("zero count needed, promises not needed.");
		}
		if (promise.getAmount() > getMissingAmount()) {
			int more = promise.getAmount() - getMissingAmount();
			//promise.numberOfItems = getMissingAmount();
			//Add Extra
			//LogisticsExtraPromise extra = new LogisticsExtraPromise(promise.item, more, promise.sender, false);
			extrapromises.add(promise.split(more));
		}
		if (promise.getAmount() <= 0) {
			throw new IllegalArgumentException("zero count ... again");
		}
		promises.add(promise);
		promiseAmount += promise.getAmount();
		root.promiseAdded(promise);
	}

	public boolean isDone() {
		return getMissingAmount() <= 0;
	}

	public boolean isAllDone() {
		boolean result = getMissingAmount() <= 0;
		for (RequestTreeNode node : subRequests) {
			result &= node.isAllDone();
		}
		return result;
	}

	protected void remove(RequestTreeNode subNode) {
		subRequests.remove(subNode);
		subNode.removeSubPromisses();
	}

	/* RequestTree helpers */
	protected void removeSubPromisses() {
		for (IPromise promise : promises) {
			root.promiseRemoved(promise);
		}
		for (RequestTreeNode subNode : subRequests) {
			subNode.removeSubPromisses();
		}
	}

	protected void checkForExtras(IResource item, HashMap<IProvide, List<IExtraPromise>> extraMap) {
		for (IExtraPromise extra : extrapromises) {
			if (item.matches(extra.getItemType(), IResource.MatchSettings.NORMAL)) {
				List<IExtraPromise> extras = extraMap.get(extra.getProvider());
				if (extras == null) {
					extras = new LinkedList<IExtraPromise>();
					extraMap.put(extra.getProvider(), extras);
				}
				extras.add(extra.copy());
			}
		}
		for (RequestTreeNode subNode : subRequests) {
			subNode.checkForExtras(item, extraMap);
		}
	}

	protected void removeUsedExtras(IResource item, HashMap<IProvide, List<IExtraPromise>> extraMap) {
		for (IPromise promise : promises) {
			if (!item.matches(promise.getItemType(), IResource.MatchSettings.NORMAL)) {
				continue;
			}
			if (!(promise instanceof IExtraPromise)) {
				continue;
			}
			IExtraPromise epromise = (IExtraPromise) promise;
			if (epromise.isProvided()) {
				continue;
			}
			int usedcount = epromise.getAmount();
			List<IExtraPromise> extras = extraMap.get(epromise.getProvider());
			if (extras == null) {
				continue;
			}
			for (Iterator<IExtraPromise> it = extras.iterator(); it.hasNext();) {
				IExtraPromise extra = it.next();
				if (extra.getAmount() >= usedcount) {
					extra.lowerAmount(usedcount);
					usedcount = 0;
					break;
				} else {
					usedcount -= extra.getAmount();
					it.remove();
				}
			}
		}
		for (RequestTreeNode subNode : subRequests) {
			subNode.removeUsedExtras(item, extraMap);
		}
	}

	protected LinkedLogisticsOrderList fullFill() {
		LinkedLogisticsOrderList list = new LinkedLogisticsOrderList();
		for (RequestTreeNode subNode : subRequests) {
			list.getSubOrders().add(subNode.fullFill());
		}
		for (IPromise promise : promises) {
			IOrderInfoProvider result = promise.fullFill(requestType, info);
			if (result != null) {
				list.add(result);
			}
		}
		for (IExtraPromise promise : extrapromises) {
			promise.registerExtras(requestType);
		}
		for (IExtraPromise promise : byproducts) {
			promise.registerExtras(requestType);
		}
		return list;
	}

	protected void buildMissingMap(Map<IResource, Integer> missing) {
		if (getMissingAmount() != 0) {
			Integer count = missing.get(getRequestType());
			if (count == null) {
				count = 0;
			}
			count += getMissingAmount();
			missing.put(getRequestType(), count);
		}
		for (RequestTreeNode subNode : subRequests) {
			subNode.buildMissingMap(missing);
		}
	}

	protected void buildUsedMap(Map<IResource, Integer> used, Map<IResource, Integer> missing) {
		int usedcount = 0;
		for (IPromise promise : promises) {
			if (promise.getType() == ResourceType.PROVIDER) {
				usedcount += promise.getAmount();
			}
		}
		if (usedcount != 0) {
			Integer count = used.get(getRequestType());
			if (count == null) {
				count = 0;
			}
			count += usedcount;
			used.put(getRequestType(), count);
		}
		if (getMissingAmount() != 0) {
			Integer count = missing.get(getRequestType());
			if (count == null) {
				count = 0;
			}
			count += getMissingAmount();
			missing.put(getRequestType(), count);
		}
		for (RequestTreeNode subNode : subRequests) {
			subNode.buildUsedMap(used, missing);
		}
	}

	private boolean checkProvider() {
		CoreRoutedPipe thisPipe = requestType.getRouter().getCachedPipe();
		if (thisPipe == null) {
			return false;
		}
		for (Pair<IProvide, List<IFilter>> provider : RequestTreeNode.getProviders(requestType.getRouter(), getRequestType())) {
			if (isDone()) {
				break;
			}
			if (provider.getValue1() == null || provider.getValue1().getRouter() == null || provider.getValue1().getRouter().getPipe() == null) {
				continue;
			}
			if (!thisPipe.sharesInterestWith(provider.getValue1().getRouter().getPipe())) {
				provider.getValue1().canProvide(this, root, provider.getValue2());
			}
		}
		return isDone();
	}

	private static List<Pair<IProvide, List<IFilter>>> getProviders(IRouter destination, IResource item) {

		// get all the routers
		BitSet routersIndex = ServerRouter.getRoutersInterestedIn(item);
		List<ExitRoute> validSources = new ArrayList<ExitRoute>(); // get the routing table
		for (int i = routersIndex.nextSetBit(0); i >= 0; i = routersIndex.nextSetBit(i + 1)) {
			IRouter r = SimpleServiceLocator.routerManager.getRouterUnsafe(i, false);

			if (!r.isValidCache()) {
				continue; //Skip Routers without a valid pipe
			}

			List<ExitRoute> e = destination.getDistanceTo(r);
			if (e != null) {
				validSources.addAll(e);
			}
		}
		// closer providers are good
		Collections.sort(validSources, new workWeightedSorter(1.0));

		List<Pair<IProvide, List<IFilter>>> providers = new LinkedList<Pair<IProvide, List<IFilter>>>();
		for (ExitRoute r : validSources) {
			if (r.containsFlag(PipeRoutingConnectionType.canRequestFrom)) {
				CoreRoutedPipe pipe = r.destination.getPipe();
				if (pipe instanceof IProvide) {
					List<IFilter> list = new LinkedList<IFilter>();
					list.addAll(r.filters);
					providers.add(new Pair<IProvide, List<IFilter>>((IProvide) pipe, list));
				}
			}
		}
		return providers;
	}

	private boolean checkExtras() {
		LinkedList<IExtraPromise> map = root.getExtrasFor(requestType);
		for (IExtraPromise extraPromise : map) {
			if (isDone()) {
				break;
			}
			if (extraPromise.getAmount() == 0) {
				continue;
			}
			boolean valid = false;
			List<ExitRoute> sources = extraPromise.getProvider().getRouter().getRouteTable().get(getRequestType().getRouter().getSimpleID());
			outer:
				for (ExitRoute source : sources) {
					if (source != null && source.containsFlag(PipeRoutingConnectionType.canRouteTo)) {
						for (ExitRoute node : getRequestType().getRouter().getIRoutersByCost()) {
							if (node.destination == extraPromise.getProvider().getRouter()) {
								if (node.containsFlag(PipeRoutingConnectionType.canRequestFrom)) {
									valid = true;
									break outer;
								}
							}
						}
					}
				}
			if (valid) {
				extraPromise.setAmount(Math.min(extraPromise.getAmount(), getMissingAmount()));
				addPromise(extraPromise);
			}
		}
		return isDone();
	}

	private boolean checkCrafting() {

		// get all the routers
		BitSet routersIndex = ServerRouter.getRoutersInterestedIn(getRequestType());
		List<ExitRoute> validSources = new ArrayList<ExitRoute>(); // get the routing table
		for (int i = routersIndex.nextSetBit(0); i >= 0; i = routersIndex.nextSetBit(i + 1)) {
			IRouter r = SimpleServiceLocator.routerManager.getRouterUnsafe(i, false);

			if (!r.isValidCache()) {
				continue; //Skip Routers without a valid pipe
			}

			List<ExitRoute> e = getRequestType().getRouter().getDistanceTo(r);
			if (e != null) {
				validSources.addAll(e);
			}
		}
		workWeightedSorter wSorter = new workWeightedSorter(0); // distance doesn't matter, because ingredients have to be delivered to the crafter, and we can't tell how long that will take.
		Collections.sort(validSources, wSorter);

		List<Pair<ICraftingTemplate, List<IFilter>>> allCraftersForItem = RequestTreeNode.getCrafters(getRequestType(), validSources);

		// if you have a crafter which can make the top treeNode.getStack().getItem()
		Iterator<Pair<ICraftingTemplate, List<IFilter>>> iterAllCrafters = allCraftersForItem.iterator();

		//a queue to store the crafters, sorted by todo; we will fill up from least-most in a balanced way.
		PriorityQueue<CraftingSorterNode> craftersSamePriority = new PriorityQueue<CraftingSorterNode>(5);
		ArrayList<CraftingSorterNode> craftersToBalance = new ArrayList<CraftingSorterNode>();
		//TODO ^ Make this a generic list
		boolean done = false;
		Pair<ICraftingTemplate, List<IFilter>> lastCrafter = null;
		int currentPriority = 0;
		outer:
			while (!done) {

			/// First: Create a list of all crafters with the same priority (craftersSamePriority).
				if (iterAllCrafters.hasNext()) {
					if (lastCrafter == null) {
						lastCrafter = iterAllCrafters.next();
					}
				} else if (lastCrafter == null) {
					done = true;
				}

			int itemsNeeded = getMissingAmount();

			if (lastCrafter != null && (craftersSamePriority.isEmpty() || (currentPriority == lastCrafter.getValue1().getPriority()))) {
					currentPriority = lastCrafter.getValue1().getPriority();
					Pair<ICraftingTemplate, List<IFilter>> crafter = lastCrafter;
					lastCrafter = null;
					ICraftingTemplate template = crafter.getValue1();
					if (isCrafterUsed(template)) {
						continue;
					}
					if (!template.canCraft(getRequestType())) {
						continue; // we this is crafting something else
					}
					for (IFilter filter : crafter.getValue2()) { // is this filtered for some reason.
						if (filter.isBlocked() == filter.isFilteredItem(template.getResultItem()) || filter.blockCrafting()) {
							continue outer;
						}
					}
					CraftingSorterNode cn = new CraftingSorterNode(crafter, itemsNeeded, root, this);
				//				if(cn.getWorkSetsAvailableForCrafting()>0)
				craftersSamePriority.add(cn);
					continue;
				}
				if (craftersToBalance.isEmpty() && (craftersSamePriority == null || craftersSamePriority.isEmpty())) {
					continue; //nothing at this priority was available for crafting
				}
				/// end of crafter prioriy selection.

				if (craftersSamePriority.size() == 1) { // then no need to balance.
					craftersToBalance.add(craftersSamePriority.poll());
					// automatically capped at the real amount of extra work.
					craftersToBalance.get(0).addToWorkRequest(itemsNeeded);
				} else {
				//				for(CraftingSorterNode c:craftersSamePriority)
				//					c.clearWorkRequest(); // so the max request isn't in there; nothing is reserved, balancing can work correctly.

				// go through this list, pull the crafter(s) with least work, add work until either they can not do more work,
					//   or the amount of work they have is equal to the next-least busy crafter. then pull the next crafter and repeat.
					if (!craftersSamePriority.isEmpty()) {
						craftersToBalance.add(craftersSamePriority.poll());
					}
					// while we crafters that can work and we have work to do.
					while (!craftersToBalance.isEmpty() && itemsNeeded > 0) {
						//while there is more, and the next crafter has the same toDo as the current one, add it to craftersToBalance.
						//  typically pulls 1 at a time, but may pull multiple, if they have the exact same todo.
						while (!craftersSamePriority.isEmpty() && craftersSamePriority.peek().currentToDo() <= craftersToBalance.get(0).currentToDo()) {
							craftersToBalance.add(craftersSamePriority.poll());
						}

					// find the most we can add this iteration
						int cap;
						if (!craftersSamePriority.isEmpty()) {
							cap = craftersSamePriority.peek().currentToDo();
						} else {
							cap = Integer.MAX_VALUE;
						}

					//split the work between N crafters, up to "cap" (at which point we would be dividing the work between N+1 crafters.
						int floor = craftersToBalance.get(0).currentToDo();
						cap = Math.min(cap, floor + (itemsNeeded + craftersToBalance.size() - 1) / craftersToBalance.size());

					Iterator<CraftingSorterNode> iter = craftersToBalance.iterator();
						while (iter.hasNext()) {
							CraftingSorterNode crafter = iter.next();
							int request = Math.min(itemsNeeded, cap - floor);
							if (request > 0) {
								int craftingDone = crafter.addToWorkRequest(request);
								itemsNeeded -= craftingDone; // ignored under-crafting
							}
						}

				} // all craftersToBalance exhausted, or work completed.

			}// end of else more than 1 crafter at this priority
				// commit this work set.
				Iterator<CraftingSorterNode> iter = craftersToBalance.iterator();
				while (iter.hasNext()) {
					CraftingSorterNode c = iter.next();
					if (c.stacksOfWorkRequested > 0 && !c.addWorkPromisesToTree()) { // then it ran out of resources
						iter.remove();
				}

				}
				itemsNeeded = getMissingAmount();

			if (itemsNeeded <= 0) {
					break outer; // we have everything we need for this crafting request
				}

				// don't clear, because we might have under-requested, and need to consider these again
				if (!craftersToBalance.isEmpty()) {
					done = false;
				//craftersSamePriority.clear(); // we've extracted all we can from these priority crafters, and we still have more to do, back to the top to get the next priority level.
				}
			}
		//LogisticsPipes.log.info("done");
		return isDone();
	}

	private class CraftingSorterNode implements Comparable<CraftingSorterNode> {

		private int stacksOfWorkRequested;
		private final int setSize;
		private final int maxWorkSetsAvailable;
		private final RequestTreeNode treeNode; // current node we are calculating

		public final Pair<ICraftingTemplate, List<IFilter>> crafter;
		public final int originalToDo;

		CraftingSorterNode(Pair<ICraftingTemplate, List<IFilter>> crafter, int maxCount, RequestTree tree, RequestTreeNode treeNode) {
			this.crafter = crafter;
			this.treeNode = treeNode;
			originalToDo = crafter.getValue1().getCrafter().getTodo();
			stacksOfWorkRequested = 0;
			setSize = crafter.getValue1().getResultStackSize();
			maxWorkSetsAvailable = ((treeNode.getMissingAmount()) + setSize - 1) / setSize;
		}

		int calculateMaxWork(int maxSetsToCraft) {

			int nCraftingSetsNeeded;
			if (maxSetsToCraft == 0) {
				nCraftingSetsNeeded = ((treeNode.getMissingAmount()) + setSize - 1) / setSize;
			} else {
				nCraftingSetsNeeded = maxSetsToCraft;
			}

			if (nCraftingSetsNeeded == 0) {
				return 0;
			}

			ICraftingTemplate template = crafter.getValue1();
			int stacks = getSubRequests(nCraftingSetsNeeded, template);

			return stacks;
		}

		int addToWorkRequest(int extraWork) {
			int stacksRequested = (extraWork + setSize - 1) / setSize;
			stacksOfWorkRequested += stacksRequested;
			return stacksRequested * setSize;
		}

		/**
		 * Add promises for the requested work to the tree.
		 */
		boolean addWorkPromisesToTree() {
			ICraftingTemplate template = crafter.getValue1();
			int setsToCraft = Math.min(stacksOfWorkRequested, maxWorkSetsAvailable);
			int setsAbleToCraft = calculateMaxWork(setsToCraft); // Deliberately outside the 0 check, because calling generatePromies(0) here clears the old ones.

			if (setsAbleToCraft > 0) { // sanity check, as creating 0 sized promises is an exception. This should never be hit.
				//if we got here, we can at least some of the remaining amount
				IPromise job = template.generatePromise(setsAbleToCraft);
				if (job.getAmount() != setsAbleToCraft * setSize) {
					throw new IllegalStateException("generatePromises not creating the promisesPromised; this is goign to end badly.");
				}
				treeNode.addPromise(job);
			}
			boolean isDone = setsToCraft == setsAbleToCraft;
			stacksOfWorkRequested = 0; // just incase we call it twice.
			return isDone;
		}

		@Override
		public int compareTo(CraftingSorterNode o) {
			return currentToDo() - o.currentToDo();
		}

		public int currentToDo() {
			return originalToDo + stacksOfWorkRequested * setSize;
		}
	}

	private static List<Pair<ICraftingTemplate, List<IFilter>>> getCrafters(IResource iRequestType, List<ExitRoute> validDestinations) {
		List<Pair<ICraftingTemplate, List<IFilter>>> crafters = new ArrayList<Pair<ICraftingTemplate, List<IFilter>>>(validDestinations.size());
		for (ExitRoute r : validDestinations) {
			CoreRoutedPipe pipe = r.destination.getPipe();
			if (r.containsFlag(PipeRoutingConnectionType.canRequestFrom)) {
				if (pipe instanceof ICraft) {
					ICraftingTemplate craftable = ((ICraft) pipe).addCrafting(iRequestType);
					if (craftable != null) {
						for (IFilter filter : r.filters) {
							if (filter.isBlocked() == filter.isFilteredItem(craftable.getResultItem()) || filter.blockCrafting()) {
								continue;
							}
						}
						List<IFilter> list = new LinkedList<IFilter>();
						list.addAll(r.filters);
						crafters.add(new Pair<ICraftingTemplate, List<IFilter>>(craftable, list));
					}
				}
			}
		}
		// don't need to sort, as a sorted list is passed in and List guarantees order preservation
		//		Collections.sort(crafters,new CraftingTemplate.PairPrioritizer());
		return crafters;
	}

	private int getSubRequests(int nCraftingSets, ICraftingTemplate template) {
		boolean failed = false;
		List<Pair<IResource, IAdditionalTargetInformation>> stacks = template.getComponents(nCraftingSets);
		int workSetsAvailable = nCraftingSets;
		ArrayList<RequestTreeNode> lastNodes = new ArrayList<RequestTreeNode>(stacks.size());
		for (Pair<IResource, IAdditionalTargetInformation> stack : stacks) {
			RequestTreeNode node = new RequestTreeNode(template, stack.getValue1(), this, RequestTree.defaultRequestFlags, stack.getValue2());
			lastNodes.add(node);
			if (!node.isDone()) {
				failed = true;
			}
		}
		if (failed) {
			for (RequestTreeNode n : lastNodes) {
				n.destroy(); // drop the failed requests.
			}
			//save last tried template for filling out the tree
			lastCrafterTried = template;
			//figure out how many we can actually get
			for (int i = 0; i < stacks.size(); i++) {
				workSetsAvailable = Math.min(workSetsAvailable, lastNodes.get(i).getPromiseAmount() / (stacks.get(i).getValue1().getRequestedAmount() / nCraftingSets));
			}

			return generateRequestTreeFor(workSetsAvailable, template);
		}
		for (IExtraPromise extra : template.getByproducts(workSetsAvailable)) {
			byproducts.add(extra);
		}
		return workSetsAvailable;
	}

	private int generateRequestTreeFor(int workSets, ICraftingTemplate template) {
		//and try it
		ArrayList<RequestTreeNode> newChildren = new ArrayList<RequestTreeNode>();
		if (workSets > 0) {
			//now set the amounts
			List<Pair<IResource, IAdditionalTargetInformation>> stacks = template.getComponents(workSets);
			boolean failed = false;
			for (Pair<IResource, IAdditionalTargetInformation> stack : stacks) {
				RequestTreeNode node = new RequestTreeNode(template, stack.getValue1(), this, RequestTree.defaultRequestFlags, stack.getValue2());
				newChildren.add(node);
				if (!node.isDone()) {
					failed = true;
				}
			}
			if (failed) {
				for (RequestTreeNode c : newChildren) {
					c.destroy();
				}
				return 0;
			}
		}
		for (IExtraPromise extra : template.getByproducts(workSets)) {
			byproducts.add(extra);
		}
		return workSets;
	}

	void recurseFailedRequestTree() {
		if (isDone()) {
			return;
		}
		if (lastCrafterTried == null) {
			return;
		}

		ICraftingTemplate template = lastCrafterTried;

		int nCraftingSetsNeeded = (getMissingAmount() + template.getResultStackSize() - 1) / template.getResultStackSize();

		List<Pair<IResource, IAdditionalTargetInformation>> stacks = template.getComponents(nCraftingSetsNeeded);

		for (Pair<IResource, IAdditionalTargetInformation> stack : stacks) {
			new RequestTreeNode(template, stack.getValue1(), this, RequestTree.defaultRequestFlags, stack.getValue2());
		}

		addPromise(template.generatePromise(nCraftingSetsNeeded));

		for (RequestTreeNode subNode : subRequests) {
			subNode.recurseFailedRequestTree();
		}
	}

	protected void logFailedRequestTree(RequestLog log) {
		Map<IResource, Integer> missing = new HashMap<IResource, Integer>();
		for (RequestTreeNode node : subRequests) {
			if (node instanceof RequestTree) {
				if (!node.isDone()) {
					node.recurseFailedRequestTree();
					node.buildMissingMap(missing);
				}
			}
		}
		log.handleMissingItems(RequestTreeNode.shrinkToList(missing));
	}

	private void destroy() {
		parentNode.remove(this);
	}

	protected static List<IResource> shrinkToList(Map<IResource, Integer> items) {
		List<IResource> resources = new ArrayList<IResource>();
		outer:
			for (Entry<IResource, Integer> entry : items.entrySet()) {
				for (IResource resource : resources) {
					if (resource.mergeForDisplay(entry.getKey(), entry.getValue())) {
						continue outer;
					}
				}
				resources.add(entry.getKey().copyForDisplayWith(entry.getValue()));
			}
		return resources;
	}
}
