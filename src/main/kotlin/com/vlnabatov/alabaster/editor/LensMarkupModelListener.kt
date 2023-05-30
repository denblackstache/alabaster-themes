package com.vlnabatov.alabaster.editor

import com.vlnabatov.alabaster.utils.DebouncingInvokeOnDispatchThread
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

/**
 * Listens for inspection highlights and reports them to [EditorLensManager].
 */
internal class LensMarkupModelListener private constructor(editor: Editor) : MarkupModelListener {
	private val lensManager = EditorLensManager.getOrCreate(editor)
	
	private val showOnDispatchThread = DebouncingInvokeOnDispatchThread(lensManager::show)
	private val hideOnDispatchThread = DebouncingInvokeOnDispatchThread(lensManager::hide)
	
	override fun afterAdded(highlighter: RangeHighlighterEx) {
		showIfValid(highlighter)
	}
	
	override fun attributesChanged(highlighter: RangeHighlighterEx, renderersChanged: Boolean, fontStyleOrColorChanged: Boolean) {
		showIfValid(highlighter)
	}
	
	override fun beforeRemoved(highlighter: RangeHighlighterEx) {
		if (getFilteredHighlightInfo(highlighter) != null) {
			hideOnDispatchThread.enqueue(highlighter)
		}
	}
	
	private fun showIfValid(highlighter: RangeHighlighter) {
		runWithHighlighterIfValid(highlighter, showOnDispatchThread::enqueue, ::showAsynchronously)
	}
	
	private fun showAsynchronously(highlighterWithInfo: HighlighterWithInfo.Async) {
		highlighterWithInfo.requestDescription {
			if (highlighterWithInfo.highlighter.isValid && highlighterWithInfo.hasDescription) {
				showOnDispatchThread.enqueue(highlighterWithInfo)
			}
		}
	}
	
	private fun showAllValid(highlighters: Array<RangeHighlighter>) {
		highlighters.forEach(::showIfValid)
	}
	
	companion object {
		private val EDITOR_KEY = Key<LensMarkupModelListener>(LensMarkupModelListener::class.java.name)
		private val MINIMUM_SEVERITY = HighlightSeverity.TEXT_ATTRIBUTES.myVal + 1
		
		private fun getFilteredHighlightInfo(highlighter: RangeHighlighter): HighlightInfo? {
			return HighlightInfo.fromRangeHighlighter(highlighter)?.takeIf { it.severity.myVal >= MINIMUM_SEVERITY }
		}
		
		private inline fun runWithHighlighterIfValid(highlighter: RangeHighlighter, actionForImmediate: (HighlighterWithInfo) -> Unit, actionForAsync: (HighlighterWithInfo.Async) -> Unit) {
			val info = highlighter.takeIf { it.isValid }?.let(::getFilteredHighlightInfo)
			if (info != null) {
				processHighlighterWithInfo(HighlighterWithInfo.from(highlighter, info), actionForImmediate, actionForAsync)
			}
		}
		
		private inline fun processHighlighterWithInfo(highlighterWithInfo: HighlighterWithInfo, actionForImmediate: (HighlighterWithInfo) -> Unit, actionForAsync: (HighlighterWithInfo.Async) -> Unit) {
			if (highlighterWithInfo is HighlighterWithInfo.Async) {
				actionForAsync(highlighterWithInfo)
			}
			else if (highlighterWithInfo.hasDescription) {
				actionForImmediate(highlighterWithInfo)
			}
		}
		
		private fun getMarkupModel(editor: Editor): MarkupModelEx? {
			return DocumentMarkupModel.forDocument(editor.document, editor.project, false) as? MarkupModelEx
		}
		
		/**
		 * Attaches a new [LensMarkupModelListener] to the [Editor], and reports all existing inspection highlights to [EditorLensManager].
		 */
		fun register(editor: Editor, disposable: Disposable) {
			if (editor.getUserData(EDITOR_KEY) != null) {
				return
			}
			
			val markupModel = getMarkupModel(editor) ?: return
			val listener = LensMarkupModelListener(editor)
			
			editor.putUserData(EDITOR_KEY, listener)
			Disposer.register(disposable) { editor.putUserData(EDITOR_KEY, null) }
			
			markupModel.addMarkupModelListener(disposable, listener)
			listener.showAllValid(markupModel.allHighlighters)
		}
		
		/**
		 * Recreates all inspection highlights in the [Editor].
		 */
		fun refresh(editor: Editor) {
			val listener = editor.getUserData(EDITOR_KEY) ?: return
			val markupModel = getMarkupModel(editor) ?: return
			
			listener.showAllValid(markupModel.allHighlighters)
		}
	}
}
