import re
import threading
import time
from multiprocessing.dummy import Pool
from queue import Queue
import random

import requests

from cli.mmt.engine import EngineNode, ApiException
from cli.mmt.processing import XMLEncoder
from cli.utils import nvidia_smi


class TranslateError(Exception):
    def __init__(self, message) -> None:
        super().__init__()
        self.message = message

    def __repr__(self):
        return '%s: %s' % (self.__class__.__name__, self.message)

    def __str__(self):
        return self.message


class TranslateEngine(object):
    def __init__(self, source_lang, target_lang):
        self.source_lang = source_lang
        self.target_lang = target_lang

    @property
    def name(self):
        raise NotImplementedError

    def _get_default_threads(self):
        raise NotImplementedError

    def translate_text(self, text):
        raise NotImplementedError

    def translate_batch(self, generator, consumer, threads=None):
        pool = Pool(threads if threads is not None else self._get_default_threads())
        jobs = Queue()

        raise_error = []

        def _consumer_thread_run():
            while True:
                job = jobs.get(block=True)

                if job is None:
                    break

                try:
                    translation = job.get()
                    consumer(translation)
                except Exception as e:
                    raise_error.append(e)
                    break

        consumer_thread = threading.Thread(target=_consumer_thread_run)
        consumer_thread.start()

        try:
            count = 0
            for line in generator:
                count += 1
                _job = pool.apply_async(self.translate_text, (line,))
                jobs.put(_job, block=True)
            return count
        finally:
            jobs.put(None, block=True)
            consumer_thread.join()
            pool.terminate()

            if len(raise_error) > 0:
                raise raise_error[0]

    def translate_stream(self, input_stream, output_stream, threads=None):
        def generator():
            for line in input_stream:
                yield line.rstrip('\n')

        def consumer(line):
            output_stream.write(line)
            output_stream.write('\n')

        return self.translate_batch(generator(), consumer, threads=threads)

    def translate_file(self, input_file, output_file, threads=None):
        with open(input_file, 'r', encoding='utf-8') as input_stream:
            with open(output_file, 'w', encoding='utf-8') as output_stream:
                return self.translate_stream(input_stream, output_stream, threads=threads)


class ModernMTTranslate(TranslateEngine):
    RE_EMOJI = re.compile('[\U00002712\U00002714\U00002716\U0000271d\U00002721\U00002728\U00002733-\U00002734'
                          '\U00002744\U00002747\U0000274c\U0000274e\U00002753-\U00002755\U00002757\U00002763-\U00002764'
                          '\U00002795-\U00002797\U000027a1\U000027b0\U000027bf\U00002934-\U00002935'
                          '\U00002b05-\U00002b07\U00002b1b-\U00002b1c\U00002b50\U00002b55\U00003030\U0000303d\U00003297'
                          '\U00003299\U0001f004\U0001f0cf\U0001f170-\U0001f171\U0001f17e-\U0001f17f\U0001f18e'
                          '\U0001f191-\U0001f19a\U0001f1e6-\U0001f1ff\U0001f201-\U0001f202\U0001f21a\U0001f22f'
                          '\U0001f232-\U0001f23a\U0001f250-\U0001f251\U0001f300-\U0001f321\U0001f324-\U0001f393'
                          '\U0001f396-\U0001f397\U0001f399-\U0001f39b\U0001f39e-\U0001f3f0\U0001f3f3-\U0001f3f5'
                          '\U0001f3f7-\U0001f4fd\U0001f4ff-\U0001f53d\U0001f549-\U0001f54e\U0001f550-\U0001f567'
                          '\U0001f56f-\U0001f570\U0001f573-\U0001f57a\U0001f587\U0001f58a-\U0001f58d\U0001f590'
                          '\U0001f595-\U0001f596\U0001f5a4-\U0001f5a5\U0001f5a8\U0001f5b1-\U0001f5b2\U0001f5bc'
                          '\U0001f5c2-\U0001f5c4\U0001f5d1-\U0001f5d3\U0001f5dc-\U0001f5de\U0001f5e1\U0001f5e3'
                          '\U0001f5e8\U0001f5ef\U0001f5f3\U0001f5fa-\U0001f64f\U0001f680-\U0001f6c5'
                          '\U0001f6cb-\U0001f6d2\U0001f6d5\U0001f6e0-\U0001f6e5\U0001f6e9\U0001f6eb-\U0001f6ec'
                          '\U0001f6f0\U0001f6f3-\U0001f6fa\U0001f7e0-\U0001f7eb\U0001f90d-\U0001f93a'
                          '\U0001f93c-\U0001f945\U0001f947-\U0001f971\U0001f973-\U0001f976\U0001f97a-\U0001f9a2'
                          '\U0001f9a5-\U0001f9aa\U0001f9ae-\U0001f9ca\U0001f9cd-\U0001f9ff\U0001fa70-\U0001fa73'
                          '\U0001fa78-\U0001fa7a\U0001fa80-\U0001fa81\U0001fa90-\U0001fa95]+')
    RE_EMOJI_TAG = re.compile(r'<re:emoji v="[^"]+" />')

    def __init__(self, node, source_lang, target_lang, priority=None,
                 context_vector=None, context_file=None, context_string=None, split_lines=False):
        TranslateEngine.__init__(self, source_lang, target_lang)
        self._api = node.api
        self._priority = EngineNode.RestApi.PRIORITY_BACKGROUND if priority is None else priority
        self._context = None
        self._split_lines = split_lines

        if context_vector is None:
            if context_file is not None:
                self._context = self._api.get_context_f(self.source_lang, self.target_lang, context_file)
            elif context_string is not None:
                self._context = self._api.get_context_s(self.source_lang, self.target_lang, context_string)
        else:
            self._context = self._parse_context_vector(context_vector)

    def _get_default_threads(self):
        executors = max(len(nvidia_smi.list_gpus()), 1)
        cluster_info = self._api.info()['cluster']
        node_count = max(len(cluster_info['nodes']), 1)

        return max(10, executors * node_count * 2)

    @property
    def context_vector(self):
        return [x.copy() for x in self._context] if self._context is not None else None

    @staticmethod
    def _parse_context_vector(text):
        context = []

        try:
            for score in text.split(','):
                _id, value = score.split(':', 2)
                value = float(value)

                context.append({
                    'memory': int(_id),
                    'score': value
                })
        except ValueError:
            raise ValueError('invalid context weights map: ' + text)

        return context

    @property
    def name(self):
        return 'ModernMT'

    @staticmethod
    def _preprocess(text):
        def repl_emoji(match):
            return '<re:emoji v="%s" />' % match.group()

        return ModernMTTranslate.RE_EMOJI.sub(repl_emoji, text)

    @staticmethod
    def _postprocess(text):
        def repl_emoji_tag(match):
            string = match.group()
            return string[string.index('"') + 1:string.rindex('"')]

        return ModernMTTranslate.RE_EMOJI_TAG.sub(repl_emoji_tag, text)

    def translate_text(self, text):
        try:
            lines = text.split('\n') if self._split_lines else [text]
            translations = []

            for line in lines:
                line = self._preprocess(line)
                translation = self._api.translate(self.source_lang, self.target_lang, line,
                                                  context=self._context, priority=self._priority)
                translations.append(self._postprocess(translation['translation']))
            return '\n'.join(translations)
        except requests.exceptions.ConnectionError:
            raise TranslateError('Unable to connect to ModernMT. '
                                 'Please check if engine is running on port %d.' % self._api.port)
        except ApiException as e:
            raise TranslateError(e.cause)

    def translate_file(self, input_file, output_file, threads=None):
        reset_context = False

        try:
            if self._context is None:
                reset_context = True
                self._context = self._api.get_context_f(self.source_lang, self.target_lang, input_file)

            return super(ModernMTTranslate, self).translate_file(input_file, output_file, threads=threads)
        except requests.exceptions.ConnectionError:
            raise TranslateError('Unable to connect to MMT. '
                                 'Please check if engine is running on port %d.' % self._api.port)
        except ApiException as e:
            raise TranslateError(e.cause)
        finally:
            if reset_context:
                self._context = None


class GoogleRateLimitError(TranslateError):
    def __init__(self, message) -> None:
        super().__init__(message)


class GoogleServerError(TranslateError):
    def __init__(self, *args, **kwargs):
        super(GoogleServerError, self).__init__(*args, **kwargs)


class GoogleTranslate(TranslateEngine):
    DEFAULT_GOOGLE_KEY = 'AIzaSyBl9WAoivTkEfRdBBSCs4CruwnGL_aV74c'

    def __init__(self, source_lang, target_lang, key=None):
        TranslateEngine.__init__(self, source_lang, target_lang)
        self._key = key if key is not None else self.DEFAULT_GOOGLE_KEY
        self._delay = 0
        self._url = 'https://translation.googleapis.com/language/translate/v2'

    @property
    def name(self):
        return 'Google Translate'

    def _get_default_threads(self):
        return 5

    @staticmethod
    def _normalize_language(lang):
        fields = lang.split('-')
        if fields[0] == "zh" and len(fields) > 1:
            if fields[1] == "CN" or fields[1] == "TW":
                return lang
        return fields[0]

    @staticmethod
    def _pack_error(request):
        json = request.json()

        if request.status_code == 403:
            for error in json['error']['errors']:
                if error['reason'] == 'dailyLimitExceeded':
                    return TranslateError('Google Translate free quota is over. Please use option --gt-key'
                                          ' to specify your GT API key.')
                elif error['reason'] == 'userRateLimitExceeded':
                    return GoogleRateLimitError('Google Translate rate limit exceeded')
        elif 500 <= request.status_code < 600:
            return GoogleServerError('Google Translate server error (%d): %s' %
                                     (request.status_code, json['error']['message']))

        return TranslateError('Google Translate error (%d): %s' % (request.status_code, json['error']['message']))

    def _increment_delay(self):
        if self._delay < 0.002:
            self._delay = 0.05
        else:
            self._delay = min(1, self._delay * 1.05)

    def _decrement_delay(self):
        self._delay *= 0.95

        if self._delay < 0.002:
            self._delay = 0

    def translate_text(self, text):
        text_has_xml = XMLEncoder.has_xml_tag(text)

        if not text_has_xml:
            text = XMLEncoder.unescape(text)

        data = {
            'model': 'nmt',
            'source': self._normalize_language(self.source_lang),
            'target': self._normalize_language(self.target_lang),
            'q': text,
            'key': self._key,
            'userip': '.'.join(map(str, (random.randint(0, 200) for _ in range(4))))
        }

        headers = {
            'X-HTTP-Method-Override': 'GET'
        }

        rate_limit_reached = False
        server_error_count = 0

        while True:
            if self._delay > 0:
                delay = self._delay * random.uniform(0.5, 1)
                time.sleep(delay)

            r = requests.post(self._url, data=data, headers=headers)

            if r.status_code != requests.codes.ok:
                e = self._pack_error(r)
                if isinstance(e, GoogleRateLimitError):
                    rate_limit_reached = True
                    self._increment_delay()
                elif isinstance(e, GoogleServerError):
                    server_error_count += 1

                    if server_error_count < 10:
                        time.sleep(1.)
                    else:
                        raise e
                else:
                    raise e
            else:
                break

        if not rate_limit_reached and self._delay > 0:
            self._decrement_delay()

        translation = r.json()['data']['translations'][0]['translatedText']

        if not text_has_xml:
            translation = XMLEncoder.escape(translation)

        return translation
