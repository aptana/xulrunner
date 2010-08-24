package com.aptana.ide.xul.browser;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.MessageFormat;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.OpenWindowListener;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.browser.WindowEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.progress.UIJob;
import org.mozilla.interfaces.nsICache;
import org.mozilla.interfaces.nsICacheService;
import org.mozilla.interfaces.nsIConsoleListener;
import org.mozilla.interfaces.nsIConsoleMessage;
import org.mozilla.interfaces.nsIConsoleService;
import org.mozilla.interfaces.nsIDOMDocument;
import org.mozilla.interfaces.nsIDOMElement;
import org.mozilla.interfaces.nsIDOMHTMLScriptElement;
import org.mozilla.interfaces.nsIDOMNode;
import org.mozilla.interfaces.nsIDOMSerializer;
import org.mozilla.interfaces.nsIDOMWindow;
import org.mozilla.interfaces.nsIPrefBranch;
import org.mozilla.interfaces.nsIPrefService;
import org.mozilla.interfaces.nsIScriptError;
import org.mozilla.interfaces.nsISupports;
import org.mozilla.interfaces.nsIWebBrowser;
import org.mozilla.interfaces.nsIWebBrowserSetup;
import org.mozilla.xpcom.Mozilla;
import org.mozilla.xpcom.XPCOMException;
import org.osgi.framework.Bundle;

import com.aptana.ide.core.FileUtils;
import com.aptana.ide.core.IdeLog;
import com.aptana.ide.core.ui.CoreUIUtils;
import com.aptana.ide.core.ui.browser.WebBrowserEditor;
import com.aptana.ide.editors.UnifiedEditorsPlugin;
import com.aptana.ide.editors.preferences.IPreferenceConstants;
import com.aptana.ide.editors.unified.ContributedBrowser;
import com.aptana.ide.editors.unified.ContributedOutline;

/**
 * Contribute xul-based firefox browser
 * 
 * @author Kevin Sawicki (ksawicki@aptana.com)
 * @author Michael Xia (mxia@aptana.com)
 */
public class FirefoxBrowser extends ContributedBrowser
{

	/**
	 * XULRUNNER_ENV
	 */
	public static final String XULRUNNER_ENV = "org.eclipse.swt.browser.XULRunnerPath"; //$NON-NLS-1$

	/**
	 * XULRUNNER_MAC_PLUGIN
	 */
	public static final String XULRUNNER_MAC_PLUGIN = "org.mozilla.xulrunner.macosx"; //$NON-NLS-1$

	/**
	 * XULRUNNER_WIN32_PLUGIN
	 */
	public static final String XULRUNNER_WIN32_PLUGIN = "org.mozilla.xulrunner.win32.win32.x86"; //$NON-NLS-1$

	/**
	 * XULRUNNER_PATH
	 */
	public static final String XULRUNNER_PATH = "/xulrunner"; //$NON-NLS-1$

	static
	{
		// It appears this call is no longer necessary on Windows as the SWT Mozilla in Eclipse 3.5+ is performing similar
		// initialization tasks, and calling it was causing a crash when initializing the profile
		if (!Platform.OS_WIN32.equals(Platform.getOS()))
		{
			FirefoxExtensionsSupport.init();
		}
	}

	private Composite errors;
	private Label errorIcon;
	private Label errorLabel;
	private Cursor hand;
	private int errorCount;
	private nsIConsoleListener errorListener = new nsIConsoleListener()
	{

		public nsISupports queryInterface(String arg0)
		{
			return null;
		}

		public void observe(nsIConsoleMessage message)
		{
			nsIScriptError error = (nsIScriptError) message.queryInterface(nsIScriptError.NS_ISCRIPTERROR_IID);
			if (error == null)
			{
				return;
			}
			if (browser == null || browser.isDisposed())
			{
				return;
			}
			long flag = error.getFlags();
			if ((flag == nsIScriptError.errorFlag || flag == nsIScriptError.exceptionFlag)
					&& error.getSourceName().equals(internalGetUrl()))
			{
				errorCount++;
				if (errorCount == 1)
				{
					String errorMessage = MessageFormat.format(Messages.getString("FirefoxBrowser.Error"), new Object[] { errorCount }); //$NON-NLS-1$
					errorIcon.setImage(Activator.getDefault().getImage(Activator.ERRORS_IMG_ID));
					errorLabel.setText(errorMessage);
					errorLabel.setToolTipText(Messages.getString("FirefoxBrowser.Errors_In_Page")); //$NON-NLS-1$
					errorIcon.setToolTipText(errorLabel.getToolTipText());
				}
				else
				{
					String errorMessage = MessageFormat.format(Messages.getString("FirefoxBrowser.Errors"), new Object[] { errorCount }); //$NON-NLS-1$
					errorLabel.setText(errorMessage);
				}
				errors.layout(true, true);
				errors.getParent().layout(true, true);
			}
		}

	};

	private ProgressListener progressListener = new ProgressListener()
	{

		public void changed(ProgressEvent event)
		{

		}

		public void completed(ProgressEvent event)
		{
			progressCompleted(event);
			handleProgressCompleted(event);
		}
	};
		
	private OpenWindowListener openWindowListener = new OpenWindowListener() {

		public void open(WindowEvent event) {
			if (!event.required || event.browser != null) {
				return;
			}
			WebBrowserEditor webBrowserEditor = WebBrowserEditor.openBlank();
			if (webBrowserEditor != null) {
				event.browser = webBrowserEditor.getBrowser();
			}
		}
	};

	private Browser browser;

	private nsIDOMDocument document;

	private ContributedOutline outline;

	private SelectionBox selectionBox = null;

	private Browser createSWTBrowser(Composite parent)
	{
		try
		{
			if (System.getProperty(XULRUNNER_ENV) == null)
			{
				Bundle bundle = null;
				if (CoreUIUtils.onWindows)
				{
					bundle = Platform.getBundle(XULRUNNER_WIN32_PLUGIN);
				}
				else if (CoreUIUtils.onMacOSX)
				{
					bundle = Platform.getBundle(XULRUNNER_MAC_PLUGIN);

				}
				if (bundle != null)
				{
					URL xulrunner = bundle.getEntry(XULRUNNER_PATH);
					if (xulrunner != null)
					{
						try
						{
							xulrunner = FileLocator.toFileURL(xulrunner);
							if (xulrunner != null)
							{
								File xulrunnerFolder = new File(xulrunner.getFile());
								String message = MessageFormat.format(
									Messages.getString("FirefoxBrowser.Setting_Path_To"), //$NON-NLS-1$
									new Object[] {
										xulrunnerFolder.getAbsolutePath()
									}
								);
								System.setProperty(XULRUNNER_ENV, xulrunnerFolder.getAbsolutePath());
								IdeLog.logInfo(Activator.getDefault(), message);
							}
						}
						catch (IOException e)
						{
							IdeLog.logError(Activator.getDefault(), Messages.getString("FirefoxBrowser.Error_Setting_Path"), e); //$NON-NLS-1$
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			IdeLog.logError(Activator.getDefault(), Messages.getString("FirefoxBrowser.Error_Setting_Path"), e); //$NON-NLS-1$
		}

		browser = new Browser(parent, SWT.MOZILLA);
		browser.addProgressListener(progressListener);
		browser.addOpenWindowListener(openWindowListener);
		
		// Disable Java
		nsIPrefService prefService = (nsIPrefService) Mozilla.getInstance().getServiceManager().getServiceByContractID("@mozilla.org/preferences-service;1", nsIPrefService.NS_IPREFSERVICE_IID); //$NON-NLS-1$
		nsIPrefBranch prefBranch = prefService.getBranch(""); //$NON-NLS-1$
		prefBranch.setBoolPref("security.enable_java", 0); //$NON-NLS-1$

		if (Platform.OS_MACOSX.equals(Platform.getOS())) {
			nsIWebBrowserSetup webBrowserSetup = (nsIWebBrowserSetup) internalGetWebBrowser().queryInterface(nsIWebBrowserSetup.NS_IWEBBROWSERSETUP_IID);
			if (webBrowserSetup != null) {
				webBrowserSetup.setProperty(nsIWebBrowserSetup.SETUP_ALLOW_PLUGINS, 0);
			}
		}
		return browser;
	}

	/**
	 * @see com.aptana.ide.editors.unified.ContributedBrowser#createControl(org.eclipse.swt.widgets.Composite)
	 */
	public void createControl(Composite parent)
	{
		browser = createSWTBrowser(parent);
		browser.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));
		errors = new Composite(parent, SWT.NONE);
		GridLayout eLayout = new GridLayout(2, false);
		eLayout.marginHeight = 1;
		eLayout.marginWidth = 1;
		eLayout.horizontalSpacing = 2;
		errors.setLayout(eLayout);
		errors.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
		hand = new Cursor(errors.getDisplay(), SWT.CURSOR_HAND);
		errorIcon = new Label(errors, SWT.LEFT);
		errorIcon.setCursor(hand);
		MouseAdapter showConsole = new MouseAdapter()
		{

			public void mouseDown(MouseEvent e)
			{
				ConsolePlugin.getDefault().getConsoleManager().showConsoleView(FirefoxConsole.getConsole());
			}

		};
		errorIcon.setLayoutData(new GridData(SWT.END, SWT.FILL, true, true));
		errorIcon.addMouseListener(showConsole);
		errorLabel = new Label(errors, SWT.LEFT);
		errorLabel.setCursor(hand);
		errorLabel.addMouseListener(showConsole);
		errorLabel.setForeground(errorLabel.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
		errorLabel.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, true));
		nsIConsoleService service = (nsIConsoleService) Mozilla.getInstance().getServiceManager()
				.getServiceByContractID("@mozilla.org/consoleservice;1", nsIConsoleService.NS_ICONSOLESERVICE_IID); //$NON-NLS-1$
		service.registerListener(errorListener);
		// Hook console
		FirefoxConsole.getConsole();
	}

	private void internalRefresh()
	{
		if (browser != null)
		{
			browser.refresh();
		}
	}

	/**
	 * @see com.aptana.ide.editors.unified.ContributedBrowser#refresh()
	 */
	public void refresh()
	{
		if (browser != null && !browser.isDisposed())
		{
			clearCache();
			clearErrors();
			internalRefresh();
		}
	}

	private void clearCache()
	{
		if (UnifiedEditorsPlugin.getDefault().getPreferenceStore().getBoolean(IPreferenceConstants.CACHE_BUST_BROWSERS))
		{
			try
			{
				nsICacheService cache = (nsICacheService) Mozilla.getInstance().getServiceManager()
						.getServiceByContractID("@mozilla.org/network/cache-service;1", //$NON-NLS-1$
								nsICacheService.NS_ICACHESERVICE_IID);
				cache.evictEntries(nsICache.STORE_ANYWHERE);
			}
			catch (Exception e)
			{
				if (e instanceof XPCOMException && ((XPCOMException)e).errorcode == Mozilla.NS_ERROR_FILE_NOT_FOUND) {
					/*Not an error since disk cache wasn't created yet  */
					return;
				}
				IdeLog.logError(Activator.getDefault(), Messages.getString("FirefoxBrowser.Error_Clearing_Cache"), e); //$NON-NLS-1$
			}
		}
	}

	/**
	 * @see com.aptana.ide.editors.unified.ContributedBrowser#dispose()
	 */
	public void dispose()
	{
		browser.dispose();
		if (hand != null && !hand.isDisposed())
		{
			hand.dispose();
		}
		errors.dispose();
		nsIConsoleService service = (nsIConsoleService) Mozilla.getInstance().getServiceManager()
				.getServiceByContractID("@mozilla.org/consoleservice;1", nsIConsoleService.NS_ICONSOLESERVICE_IID); //$NON-NLS-1$
		service.unregisterListener(errorListener);
	}

	/**
	 * @see com.aptana.ide.editors.unified.ContributedBrowser#getControl()
	 */
	public Control getControl()
	{
		return browser;
	}

	private void clearErrors()
	{
		errorCount = 0;
		errorIcon.setImage(Activator.getDefault().getImage(Activator.NO_ERRORS_IMG_ID));
		errorLabel.setText(""); //$NON-NLS-1$
		errorLabel.setToolTipText(""); //$NON-NLS-1$
		errorIcon.setToolTipText(Messages.getString("FirefoxBrowser.No_Errors_On_Page")); //$NON-NLS-1$
		errors.layout(true, true);
	}

	/**
	 * @see com.aptana.ide.editors.unified.ContributedBrowser#setURL(java.lang.String)
	 */
	public void setURL(String url)
	{
		clearCache();
		clearErrors();
		internalSetUrl(url);
	}

	private void internalSetUrl(String url)
	{
		if (browser != null)
		{
			browser.setUrl(url);
		}
	}

	private String internalGetUrl()
	{
		if (browser != null)
		{
			return browser.getUrl();
		}
		return null;
	}

	/**
	 * Gets the DOM document object
	 * 
	 * @return - dom document
	 */
	public nsIDOMDocument getDocument()
	{
		return this.document;
	}

	/**
	 * @see com.aptana.ide.editors.unified.ContributedBrowser#setOutline(com.aptana.ide.editors.unified.ContributedOutline)
	 */
	public void setOutline(ContributedOutline outline)
	{
		this.outline = outline;
	}

	private void handleProgressCompleted(ProgressEvent event)
	{
		document = internalGetDocument();
		if (document != null)
		{
			selectionBox = new SelectionBox(document);
		}
		else
		{
			IdeLog.logError(Activator.getDefault(), Messages.getString("FirefoxBrowser.Cannot_Get_Document")); //$NON-NLS-1$
		}
		if (outline != null)
		{
			outline.refresh();
		}
	}

	/**
	 * Highlights an element in this browser
	 * 
	 * @param element -
	 *            element to highlight
	 */
	public void highlightElement(nsIDOMNode element)
	{
		if (element.getNodeType() == nsIDOMNode.ELEMENT_NODE)
		{
			selectionBox.highlight((nsIDOMElement) element.queryInterface(nsIDOMElement.NS_IDOMELEMENT_IID));
		}
		else
		{
			selectionBox.hide();
		}
	}

	/**
	 * @see com.aptana.ide.editors.unified.ContributedBrowser#back()
	 */
	public void back()
	{
		if (browser != null)
		{
			browser.back();
		}
	}

	/**
	 * @see com.aptana.ide.editors.unified.ContributedBrowser#forward()
	 */
	public void forward()
	{
		if (browser != null)
		{
			browser.forward();
		}
	}

	/**
	 * @see com.aptana.ide.editors.unified.ContributedBrowser#getUnderlyingBrowserObject()
	 */
	public Object getUnderlyingBrowserObject()
	{
		return browser;
	}

	private nsIWebBrowser internalGetWebBrowser()
	{
		Object retVal = browser.getWebBrowser();
		if (retVal instanceof nsIWebBrowser)
		{
			return (nsIWebBrowser) retVal;
		}
		return null;
	}

	private nsIDOMDocument internalGetDocument()
	{
		nsIWebBrowser webBrowser = internalGetWebBrowser();
		nsIDOMDocument nsidomdocument = null;
		if (webBrowser != null)
		{
			nsIDOMWindow nsidomwindow = webBrowser.getContentDOMWindow();
			nsidomdocument = nsidomwindow.getDocument();
		}
		return nsidomdocument;
	}

	/**
	 * @see com.aptana.ide.editors.unified.ContributedBrowser#execute(java.lang.String)
	 */
	public boolean execute(String script)
	{
		nsIDOMDocument document = internalGetDocument();
		if (document != null)
		{
			nsIDOMElement se = document.createElement("script"); //$NON-NLS-1$
			nsIDOMHTMLScriptElement scriptBlock = (nsIDOMHTMLScriptElement) se
					.queryInterface(nsIDOMHTMLScriptElement.NS_IDOMHTMLSCRIPTELEMENT_IID);
			String s2 = "if(" + script + "){" + "document.getElementById('execute').setAttribute('text','success');}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			scriptBlock.setText(s2);
			nsIDOMElement executeBlock = document.getElementById("execute"); //$NON-NLS-1$
			if (executeBlock == null)
			{
				executeBlock = document.createElement("div"); //$NON-NLS-1$
				executeBlock.setAttribute("id", "execute"); //$NON-NLS-1$ //$NON-NLS-2$
				nsIDOMNode body = document.getElementsByTagName("body").item(0); //$NON-NLS-1$
				body.appendChild(executeBlock);
			}
			executeBlock.setAttribute("text", ""); //$NON-NLS-1$ //$NON-NLS-2$
			nsIDOMNode head = document.getElementsByTagName("head").item(0); //$NON-NLS-1$
			head.appendChild(scriptBlock);
			executeBlock = document.getElementById("execute"); //$NON-NLS-1$
			return "success".equals(executeBlock.getAttribute("text")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		else
		{
			return false;
		}
	}

	/**
	 * @see com.aptana.ide.editors.unified.ContributedBrowser#displaySource()
	 */
	public void displaySource()
	{
		if (document != null)
		{
			nsIDOMSerializer serializer = (nsIDOMSerializer) Mozilla.getInstance().getComponentManager()
					.createInstanceByContractID("@mozilla.org/xmlextras/xmlserializer;1", null, //$NON-NLS-1$
							nsIDOMSerializer.NS_IDOMSERIALIZER_IID);
			String source = serializer.serializeToString(document.getDocumentElement());
			try
			{
				final String newFileName = FileUtils.getRandomFileName("source", ".html"); //$NON-NLS-1$ //$NON-NLS-2$
				final File temp = new File(FileUtils.systemTempDir + File.separator + newFileName);
				FileUtils.writeStringToFile(source, temp);
				UIJob openJob = new UIJob(Messages.getString("FirefoxBrowser.Open_Source_Editor")) //$NON-NLS-1$
				{

					public IStatus runInUIThread(IProgressMonitor monitor)
					{
						IEditorInput input = CoreUIUtils.createJavaFileEditorInput(temp);
						try
						{
							IDE.openEditor(Activator.getDefault().getWorkbench().getActiveWorkbenchWindow()
									.getActivePage(), input, IDE.getEditorDescriptor(newFileName).getId());
						}
						catch (PartInitException e)
						{
							e.printStackTrace();
						}
						return Status.OK_STATUS;
					}

				};
				openJob.schedule();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}

		}
	}

    /**
     * @see com.aptana.ide.core.ui.browser.IBrowser#addLocationListener(org.eclipse.swt.browser.LocationListener)
     */
    public void addLocationListener(LocationListener listener)
    {
    	if (browser != null)
    	{
    		browser.addLocationListener(listener);
    	}
    }

    /**
     * @see com.aptana.ide.core.ui.browser.IBrowser#removeLocationListener(org.eclipse.swt.browser.LocationListener)
     */
    public void removeLocationListener(LocationListener listener)
    {
        if (browser != null)
        {
            browser.removeLocationListener(listener);
        }
    }
}
