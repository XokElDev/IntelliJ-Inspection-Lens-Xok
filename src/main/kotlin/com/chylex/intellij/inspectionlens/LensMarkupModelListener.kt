package com.chylex.intellij.inspectionlens

import com.chylex.intellij.inspectionlens.util.MultiParentDisposable
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.TextEditor

/**
 * Listens for inspection highlights and reports them to [EditorInlayLensManager].
 */
class LensMarkupModelListener private constructor(editor: Editor) : MarkupModelListener {
	private val lens = EditorInlayLensManager.getOrCreate(editor)
	
	override fun afterAdded(highlighter: RangeHighlighterEx) {
		showIfValid(highlighter)
	}
	
	override fun attributesChanged(highlighter: RangeHighlighterEx, renderersChanged: Boolean, fontStyleOrColorChanged: Boolean) {
		showIfValid(highlighter)
	}
	
	override fun beforeRemoved(highlighter: RangeHighlighterEx) {
		lens.hide(highlighter)
	}
	
	private fun showIfValid(highlighter: RangeHighlighter) {
		runWithHighlighterIfValid(highlighter, lens::show, ::showAsynchronously)
	}
	
	private fun showAllValid(highlighters: Array<RangeHighlighter>) {
		val immediateHighlighters = mutableListOf<HighlighterWithInfo>()
		
		for (highlighter in highlighters) {
			runWithHighlighterIfValid(highlighter, immediateHighlighters::add, ::showAsynchronously)
		}
		
		lens.showAll(immediateHighlighters)
	}
	
	private fun showAsynchronously(highlighterWithInfo: HighlighterWithInfo.Async) {
		highlighterWithInfo.requestDescription {
			if (highlighterWithInfo.highlighter.isValid && highlighterWithInfo.hasDescription) {
				val application = ApplicationManager.getApplication()
				if (application.isDispatchThread) {
					lens.show(highlighterWithInfo)
				}
				else {
					application.invokeLater {
						lens.show(highlighterWithInfo)
					}
				}
			}
		}
	}
	
	companion object {
		private val MINIMUM_SEVERITY = HighlightSeverity.TEXT_ATTRIBUTES.myVal + 1
		
		private inline fun runWithHighlighterIfValid(highlighter: RangeHighlighter, actionForImmediate: (HighlighterWithInfo) -> Unit, actionForAsync: (HighlighterWithInfo.Async) -> Unit) {
			if (!highlighter.isValid) {
				return
			}
			
			val info = HighlightInfo.fromRangeHighlighter(highlighter)
			if (info == null || info.severity.myVal < MINIMUM_SEVERITY) {
				return
			}
			
			processHighlighterWithInfo(HighlighterWithInfo.from(highlighter, info), actionForImmediate, actionForAsync)
		}
		
		private inline fun processHighlighterWithInfo(highlighterWithInfo: HighlighterWithInfo, actionForImmediate: (HighlighterWithInfo) -> Unit, actionForAsync: (HighlighterWithInfo.Async) -> Unit) {
			if (highlighterWithInfo is HighlighterWithInfo.Async) {
				actionForAsync(highlighterWithInfo)
			}
			else if (highlighterWithInfo.hasDescription) {
				actionForImmediate(highlighterWithInfo)
			}
		}
		
		/**
		 * Attaches a new [LensMarkupModelListener] to the document model of the provided [TextEditor], and reports all existing inspection highlights to [EditorInlayLensManager].
		 * 
		 * The [LensMarkupModelListener] will be disposed when either the [TextEditor] is disposed, or via [InspectionLensPluginDisposableService] when the plugin is unloaded.
		 */
		fun install(textEditor: TextEditor) {
			val editor = textEditor.editor
			val markupModel = DocumentMarkupModel.forDocument(editor.document, editor.project, false)
			if (markupModel is MarkupModelEx) {
				val pluginDisposable = ApplicationManager.getApplication().getService(InspectionLensPluginDisposableService::class.java)
				
				val listenerDisposable = MultiParentDisposable()
				listenerDisposable.registerWithParent(textEditor)
				listenerDisposable.registerWithParent(pluginDisposable)
				
				val listener = LensMarkupModelListener(editor)
				markupModel.addMarkupModelListener(listenerDisposable.self, listener)
				listener.showAllValid(markupModel.allHighlighters)
			}
		}
	}
}
